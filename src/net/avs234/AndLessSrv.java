package net.avs234;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;


public class AndLessSrv extends Service {
	
	static {
		System.loadLibrary("lossless");
	}
	
	public static native int 		audioInit(int ctx, int mode);	
	public static native boolean	audioExit(int ctx);
	public static native boolean	audioStop(int ctx);
	public static native boolean	audioPause(int ctx);
	public static native boolean	audioResume(int ctx);
	
	public static native int		audioGetDuration(int ctx);
	public static native int		audioGetCurPosition(int ctx);
	public static native boolean	audioSetVolume(int ctx, int vol);
	
	public static native int		alacPlay(int ctx,String file, int start);
	public static native int		flacPlay(int ctx,String file, int start);
	public static native int		apePlay(int ctx,String file, int start);
	public static native int		wavPlay(int ctx,String file, int start);
	public static native int		wvPlay(int ctx,String file, int start);
	public static native int		mpcPlay(int ctx,String file, int start);
	public static native int []		extractFlacCUE(String file);
	
	public static native int		wvDuration(int ctx,String file);
	public static native int		apeDuration(int ctx,String file);
	
	/*
	public native int 		audioInit(int ctx, int mode);	
	public native boolean	audioExit(int ctx);
	public native boolean	audioStop(int ctx);
	public native boolean	audioPause(int ctx);
	public native boolean	audioResume(int ctx);
	
	public native int		audioGetDuration(int ctx);
	public native int		audioGetCurPosition(int ctx);
	public native boolean	audioSetVolume(int ctx, int vol);
	
	public native int		alacPlay(int ctx,String file, int start);
	public native int		flacPlay(int ctx,String file, int start);
	public native int		apePlay(int ctx,String file, int start);
	public native int		wavPlay(int ctx,String file, int start);
	public native int		wvPlay(int ctx,String file, int start);
	public native int		mpcPlay(int ctx,String file, int start);
	public native int []	extractFlacCUE(String file);
	*/
	
		
	// errors returned by xxxPlay functions
	public static final int LIBLOSSLESS_ERR_NOCTX = 1;
	public static final int LIBLOSSLESS_ERR_INV_PARM  =  2;
	public static final int LIBLOSSLESS_ERR_NOFILE = 3;
	public static final int LIBLOSSLESS_ERR_FORMAT = 4;
	public static final int LIBLOSSLESS_ERR_AU_GETCONF = 6;
	public static final int LIBLOSSLESS_ERR_AU_SETCONF = 7;
	public static final int LIBLOSSLESS_ERR_AU_BUFF = 8;
	public static final int LIBLOSSLESS_ERR_AU_SETUP = 9;
	public static final int LIBLOSSLESS_ERR_AU_START = 10;
	public static final int LIBLOSSLESS_ERR_IO_WRITE = 11;
	public static 	final int LIBLOSSLESS_ERR_IO_READ = 12;
	public static final int LIBLOSSLESS_ERR_DECODE = 13;
	public static final int LIBLOSSLESS_ERR_OFFSET = 14;
	public static final int LIBLOSSLESS_ERR_NOMEM = 15;
	
	// Audio context descriptor returned by audioInit(). 
	// Actually, it's a pointer to struct msm_ctx, see native code.
	// Used in all subsequent calls of native functions.
	private static int ctx = 0;
		
	public static final int MODE_NONE = 0;
	public static final int MODE_DIRECT = 1; 
	public static final int MODE_LIBMEDIA = 2;
	public static final int MODE_CALLBACK = 3;
	
	// Ad hoc value. 0x2000 seems to be a maximum used by the driver. MSM datasheets needed. 
	private int volume = 0x1000;
	
	// The lock to acquire so as the device won't go to sleep when we'are playing.  
	private PowerManager.WakeLock wakeLock = null;
	
	private NotificationManager nm = null;
	
	private void log_msg(String msg) {
		Log.i(getClass().getSimpleName(), msg);
	}
	private void log_err(String msg) {
		Log.e(getClass().getSimpleName(), msg);
	}
	
	public static int curTrackLen = 0;
	public static int curTrackStart = 0;
	private static int last_cue_start = 0;
	private static int total_cue_len = 0;
		
	// Callback to be called from native code
	
	public static void updateTrackLen(int time) {
		if(last_cue_start == -1) {
			total_cue_len = time;	// track time already updated from the cue, just save total length for the last track  
			return;	
		}
		curTrackLen = time - last_cue_start;
	}
	
	// Callback used to send new track name or error status to the interface thread.
	
	private static final RemoteCallbackList<IAndLessSrvCallback> cBacks = new RemoteCallbackList<IAndLessSrvCallback>();
	
	private void informTrack(String s, boolean error) {
		final int k = cBacks.beginBroadcast();
		for (int i=0; i < k; i++) {
	         try { 
	            	if(!error) {
	            		cBacks.getBroadcastItem(i).playItemChanged(false,s);
						AndLessSrv.this.notify(R.drawable.play_on/*R.drawable.playbackstart*/,s);
	            	} else {
	            		cBacks.getBroadcastItem(i).playItemChanged(true,getString(R.string.strStopped));
	            		if(s.compareTo(getString(R.string.strStopped))!= 0) cBacks.getBroadcastItem(i).errorReported(s);
	            	}
	         } catch (RemoteException e) { 
	        	 log_err("remote exception in informTrack(): " + e.toString());
	        	 break;
	         }
	    }
	    cBacks.finishBroadcast();
	}

	
	private MediaPlayer mplayer = null;
	private Object mplayer_lock= new Object();
	private int fck_start;
	private boolean isPrepared;
	public int extPlay(String file, int start) {
		try {
			isPrepared = false;
			if(mplayer == null) {
				mplayer = new MediaPlayer();
			}
			fck_start = start;
			if(mplayer == null) return LIBLOSSLESS_ERR_NOCTX;
			mplayer.setDataSource(file);
			mplayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
				public boolean onError(MediaPlayer mp, int what, int extra) {
					if(mplayer != null) synchronized(mplayer_lock) {
						mplayer.release(); 	mplayer = null;
					}
					log_err("mplayer playback aborted with errors: " + what + ", " + extra);
					informTrack(getString(R.string.strMplayerError),true);
					mplayer_lock.notify();
					return false;
				}
			});
			mplayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
				public void onCompletion(MediaPlayer mp) {
					log_msg("mplayer playback completed");
					synchronized(mplayer_lock) {
						mplayer_lock.notify();
					}
				}
			});
			mplayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
				public void onPrepared(MediaPlayer mp) {
					isPrepared = true;
					if(fck_start != 0) mplayer.seekTo(fck_start*1000);
					curTrackLen = mplayer.getDuration()/1000;
					mplayer.start();
				}
			});
			
			mplayer.prepare();
			//SystemClock.sleep(250);
			//if(!mplayer.isPlaying()) return LIBLOSSLESS_ERR_INV_PARM;
			synchronized(mplayer_lock) {
				mplayer_lock.wait();
			}
		} catch (Exception e) { 
       	 	log_err("Exception in extPlay(): " + e.toString());
       	 	return LIBLOSSLESS_ERR_INV_PARM;
		} finally  {
			synchronized(mplayer_lock) {
				if(mplayer != null) {
					if(mplayer.isPlaying()) mplayer.stop();
					mplayer.release();
				}
				mplayer = null;
			}
		}
		return 0;
	}

	private static final int NOTIFY_ID = R.drawable.icon;	
	private void notify(int icon, String s) {
		if(nm == null) return;
		if(s == null) {
			nm.cancel(NOTIFY_ID);
			return;
		}
		final Notification notty = new Notification(icon, s, System.currentTimeMillis());
		Intent intent = new Intent();
		intent.setAction("android.intent.action.MAIN");
		intent.addCategory("android.intent.category.LAUNCHER");
		intent.setClass(this, AndLess.class);
		intent.setFlags(0x10100000);
		notty.setLatestEventInfo(getApplicationContext(), "andLess", s, 
					PendingIntent.getActivity(this, 0, intent, 0));
		nm.notify(NOTIFY_ID,notty);
	}
	
	////////////////////////////////////////////////////////////////
	//////////////////////// Main class ////////////////////////////
	////////////////////////////////////////////////////////////////
		
	private class playlist {
			
		private String 	 	dir;		// source file(s) path
		private String[]	files;		// track source files
		private String[]	names;		// track names from cue files 
		private int[]		times; 		// track start times from cue files
		private	int 		cur_pos;	// current track
		private int			cur_start;	// start seconds the file must be played
		private PlayThread 	th;			// main thread
		private boolean		running;	// either playing or paused
		private boolean		paused;
		private CueUpdater	cup;		// updater for cue playlists
		private int			cur_mode;		// MODE_NONE for mp3, or one of driver_mode
		private int			driver_mode;	// driver mode in client preferences
		
		public boolean init_playlist(String path, int items) {
			if(items <= 0) return false;

			dir = null;  	files = null;
			cur_pos = -1;	th = null;
			paused = false;	running = false; 
			times = null;	names = null;
			cup = null;		cur_mode = MODE_NONE;
			driver_mode = MODE_CALLBACK;

			if(path != null) dir = new String(path);
			try {
				files = new String[items];
				names = new String[items];
				times = new int[items];
			} catch (Exception e) {
				log_err("exception in init_playlist(): " + e.toString());
				return false;
			}
			return true;
		}	
		
		public boolean add_to_playlist(String track_source, String track_name, int start_time, int pos) {
			if(pos >= files.length) return false;
			if(track_source == null) return false;
			try {
				files[pos] = new String(track_source); 
				if(track_name != null) names[pos] = new String(track_name); else names[pos] = null;
				times[pos] = start_time;
			} catch (Exception e) {
				log_err("exception in add_to_playlist(): " + e.toString());
				return false;
			}
			return true;
		}
		
		// Used for cue files to update current track number and name.
		private class CueUpdater {
			private class CUETimerTask extends TimerTask {
				public void run() {
					cur_pos++; cur_start = 0;
					if(cur_pos < names.length && names[cur_pos] != null) {
						log_msg("track name = " + names[cur_pos]);
						if(cur_pos + 1 < files.length) curTrackLen = times[cur_pos+1] - times[cur_pos];
						else curTrackLen = total_cue_len - times[cur_pos];
						curTrackStart = getCurPosition(); // audioGetCurPosition(ctx);
						informTrack(names[cur_pos],false);
					}
					if(cur_pos + 1 < names.length) schedule((times[cur_pos+1] - times[cur_pos])*1000);
				}
			}	
			private Timer timer;
			private CUETimerTask timer_task;
			private long last_time_left;
			private long last_time_start;
			
			public void shutdown() {
				if(timer_task != null) timer_task.cancel();
				if(timer != null) timer.cancel();
			}
			private void reset() {
				shutdown();
				timer_task = new CUETimerTask();
				timer = new Timer();
			}
			public synchronized void schedule(long delay) {
				reset();
				last_time_left = delay;
				last_time_start = System.currentTimeMillis();
				timer.schedule(timer_task, delay);
			}
			public synchronized void pause() {
				reset();
				last_time_left -= (System.currentTimeMillis() - last_time_start); 
			}
			public synchronized void resume() {
				last_time_start = System.currentTimeMillis();
				timer.schedule(timer_task, last_time_left);
			}
		}
				
		private boolean permsOkay = false;
		
		private boolean initAudioMode(int mode) {
			if(mode == MODE_DIRECT && !permsOkay) {
				if(checkSetDevicePermissions()) {
					log_msg("checkSetDevicePermissions() returned OK");
					permsOkay = true;
				} else {
					log_msg("checkSetDevicePermissions() returned error!");
					return false;
				}
			}
			log_msg("initAudioMode(" + mode + "), permsOkay=" + permsOkay);
			try {
		   		ctx = audioInit(ctx,mode);
	   		} catch(Exception e) { 
		   		log_err("exception in audioInit(): " + e.toString());
		   		return false;
	   		}
	   		if(ctx == 0) {
		   		log_err("audioInit() failed");
		   		return false;
	   		}
           	if(mode == MODE_DIRECT) audioSetVolume(ctx,volume);
			cur_mode = mode;
           	return true;
		}
				
		private int getCurPosition() {
		//	if(!running) return 0;
			if(cur_mode == MODE_NONE) {
				if(mplayer != null) return mplayer.getCurrentPosition()/1000;
			} 
			if(ctx == 0) return 0;
			return audioGetCurPosition(ctx) + cur_start;
		}
	
		private int getDuration() {
		//	if(!running) return 0;
			if(cur_mode == MODE_NONE) {
				do {
					SystemClock.sleep(200);
		        } while (!isPrepared);
				if(mplayer != null && isPrepared) return mplayer.getDuration()/1000;
			} 
			if(ctx == 0) return 0;
			return audioGetDuration(ctx);
		}
				
		private class PlayThread extends Thread {
			private int tid = -1;
			public void run() {
				tid = Process.myTid();
				running = true;
				log_msg("run(): starting new thread " + tid);
				Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
				if(!wakeLock.isHeld()) wakeLock.acquire();
				int k;
				for(k = 1; running && cur_pos < files.length; cur_pos++) {
					log_msg(Process.myTid() + ": trying " + files[cur_pos] + " @ time " + (times[cur_pos] + cur_start) +" mode=" + driver_mode);
					try {
						curTrackLen = 0;
						curTrackStart = 0;
						last_cue_start = 0;
						total_cue_len = 0;
						
						if(names[cur_pos] != null) {	// this is CUE track
							log_msg("track name = " + names[cur_pos]);
							if(cur_pos + 1 < files.length) {
								informTrack(names[cur_pos],false);
								curTrackLen = times[cur_pos+1]-times[cur_pos]; // native thread won't update this track length but will save total_cue_len  
								last_cue_start = -1;						   // so that we'll be able to update the last track length in due time from CueUpdater 
								cup = new CueUpdater();
								cup.schedule((times[cur_pos+1] - times[cur_pos])*1000 - cur_start);
							} else {	// last CUE track				
								informTrack(names[cur_pos],false);	
								last_cue_start = times[cur_pos]; // native thread will subtract this from the total cue length 
							}
						} else {
							String cur_file = files[cur_pos];
							int start = cur_file.lastIndexOf("/") + 1; 
							int end = cur_file.lastIndexOf(".");
							String cf = end > start ? cur_file.substring(start,end) : cur_file.substring(start);
							informTrack(cf,false);
						}
	              		if(files[cur_pos].endsWith(".ape") || files[cur_pos].endsWith(".APE")) {
	              			if(initAudioMode(driver_mode))	k = apePlay(ctx,files[cur_pos],times[cur_pos]+cur_start);
	              		} else if(files[cur_pos].endsWith(".flac") || files[cur_pos].endsWith(".FLAC")) {
	              			if(initAudioMode(driver_mode)) k = flacPlay(ctx,files[cur_pos],times[cur_pos]+cur_start);
	              		} else if(files[cur_pos].endsWith(".m4a") || files[cur_pos].endsWith(".M4A")) {
	              			if(initAudioMode(driver_mode)) k = alacPlay(ctx,files[cur_pos],times[cur_pos]+cur_start);
	              			if(k == LIBLOSSLESS_ERR_FORMAT) {	// maybe it's not apple lossless
	              				if(initAudioMode(MODE_NONE)) k = extPlay(files[cur_pos],times[cur_pos]+cur_start);
	              			}
	              		} else if(files[cur_pos].endsWith(".wav") || files[cur_pos].endsWith(".WAV")) {
							if(initAudioMode(driver_mode)) k = wavPlay(ctx,files[cur_pos],times[cur_pos]+cur_start);
						} else if(files[cur_pos].endsWith(".wv") || files[cur_pos].endsWith(".WV")) {
							if(initAudioMode(driver_mode)) k = wvPlay(ctx,files[cur_pos],times[cur_pos]+cur_start);
						} else if(files[cur_pos].endsWith(".mpc") || files[cur_pos].endsWith(".MPC")) {
							if(initAudioMode(driver_mode)) k = mpcPlay(ctx,files[cur_pos],times[cur_pos]+cur_start);
						} else {
							if(initAudioMode(MODE_NONE)) k = extPlay(files[cur_pos],times[cur_pos]+cur_start);
						}
	              		nm.cancel(NOTIFY_ID);
					} catch(Exception e) { 
						log_err("run(): exception in xxxPlay(): " + e.toString());
						cur_start = 0;
						continue;
					}
					cur_start = 0;
					if(k == 0) log_msg(Process.myTid() + ": xxxPlay() returned normally");
					else {
						log_err(String.format("run(): xxxPlay() returned error %d",k));
						running = false;
						String err, s[] = getResources().getStringArray(R.array.Errors);
							try  {
								err = s[k-1];
							} catch(Exception e) {
								err = getString(R.string.strInternalError);
							}
							informTrack(err,true);
						break;
					}
					if(names[0]!= null) break; // just in case
				}
				if(wakeLock.isHeld()) wakeLock.release();
				log_msg(Process.myTid() + ": thread about to exit");
				if(k == 0) informTrack(getString(R.string.strStopped),true);
				try {
					if(cup != null) cup.shutdown();
				} catch (Exception e) {
					log_err("Timer exception in run(): " + e.toString());
				}
				cup = null;
				running = false;
			}
			public int getThreadId() {
				return tid;
			}
		}		
		public boolean stop() {
			running = false;
			log_msg("stop()");
		    nm.cancel(NOTIFY_ID);
			if(th != null) { 
				int i = Process.getThreadPriority(Process.myTid()); 
				int tid = th.getThreadId();
				int k = 0;
				
				try {
					if(cup != null) cup.shutdown();
				} catch (Exception e) {
					log_err("Timer exception in stop(): " + e.toString());
				}
				cup = null;
				log_msg(String.format("stop(): terminating thread %d from %d", tid, Process.myTid()));
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
				if(mplayer == null) audioStop(ctx);
				else {
					synchronized(mplayer_lock) {
						mplayer_lock.notify();
					}
				}
				if(paused) paused = false;
				try {
					while(th.isAlive()) {	
						th.join(100); k++;
						if(th.isAlive()) SystemClock.sleep(50);
						else break;
						if(k > 20) break;
					}
				} catch (InterruptedException e) {
					log_err("Interrupted exception in stop(): " + e.toString());
				}
				if(th.isAlive()) log_err(String.format("stop(): thread %d is still alive after %d ms",tid, k*100));
				else log_msg(String.format("stop(): thread terminated after %d ms",k*100));
				th = null;
				Process.setThreadPriority(i);
			} else log_msg(String.format("stop(): player thread was null (my tid %d)", Process.myTid()));
			return true;
		}
		public boolean play(int n, int start) {
			log_msg(String.format("play(%d)",n));
			stop();
			if(files == null || n >= files.length || n < 0) return false;
			cur_pos= n; cur_start = start;
			th = new PlayThread();
			log_msg(String.format("play(): created new thread from %d", Process.myTid()));
			th.start();
			return true;
		}
		public boolean seekTo(int p) {
			log_msg(String.format("seekTo(%d)",p));
			mplayer.seekTo(p);
			return true;
		}
		public boolean play_next() {
			log_msg("play_next()");
			return play(cur_pos+1,0);
		}
		public boolean play_prev() {
			log_msg("play_prev()");
			return play(cur_pos-1,0);
		}
		public boolean pause() {
			log_msg("pause()");
			if(files == null || paused) return false;
				
			if(mplayer != null) {
				paused = true;
				try {
					mplayer.pause();					
				} catch (Exception e) {
					log_err("Mplayer exception in pause(): " + e.toString());
					paused = false;
				}
			} else {
				int i = Process.getThreadPriority(Process.myTid()); 
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
				if(audioPause(ctx)) paused = true;
				Process.setThreadPriority(i);
			}
			if(wakeLock.isHeld()) wakeLock.release();
			if(cup != null) cup.pause();
			return paused == true;
		}
		public boolean resume() {
			log_msg("resume()");
			if(files == null || !paused) return false;
			if(mplayer != null) {
				paused = false;
				try {
					mplayer.start();					
				} catch (Exception e) {
					log_err("Mplayer exception in resume(): " + e.toString());
					paused = true;
				}
			} else {
				int i = Process.getThreadPriority(Process.myTid()); 
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
				if(audioResume(ctx)) paused = false;
				Process.setThreadPriority(i);
			}

			if(!paused && !wakeLock.isHeld()) wakeLock.acquire();
			if(cup != null) cup.resume();
			return paused == false;
		}
		public boolean dec_vol() {
			log_msg("dec_vol()");
			if(files == null || !running) return false;
			if(mplayer != null) return true;
			int i = Process.getThreadPriority(Process.myTid()); 
			Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
			if(volume >= 0x400) {
				volume -= 0x400;
				audioSetVolume(ctx, volume);
			} else if(volume != 0) audioSetVolume(ctx, 0);
			Process.setThreadPriority(i);
			return true;
		}
		public boolean inc_vol() {
			log_msg("inc_vol()");
			if(files == null || !running) return false;
			if(mplayer != null) return true;
			int i = Process.getThreadPriority(Process.myTid()); 
			Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
			if(volume <= 0x2000) {
		        volume += 0x400;
		        audioSetVolume(ctx, volume);
			}
			Process.setThreadPriority(i);
			return true;
		}
	}
	
	private static playlist plist = null;

	//////////////////////////////////////////////////
	//// The interface we expose to clients.  It's returned to them when the connection is established. 

	private static final IAndLessSrv.Stub binder = new IAndLessSrv.Stub() {
		public boolean init_playlist(String path, int nitems) {
			plist.stop(); //	plist = null; 		plist = new playlist(); 
			return plist.init_playlist(path,nitems); 
		}
		public boolean  add_to_playlist(String src, String name, int start_time, int pos) { return plist.add_to_playlist(src,name,start_time,pos);}
		public boolean	play (int n, int start) 	{ return plist.play(n,start); }
		public boolean	seek_to (int p) 	{ return plist.seekTo(p); }
		public boolean	play_next()  	{ return plist.play_next(); }
		public boolean	play_prev()  	{ return plist.play_prev(); }
		public boolean	pause() 		{ return plist.pause(); }
		public boolean	resume() 		{ return plist.resume(); }
		public boolean	inc_vol() 		{ return plist.inc_vol(); }
		public boolean	dec_vol() 		{ return plist.dec_vol(); }
		public boolean	shutdown() 		{ plist.stop();  if(ctx != 0) audioExit(ctx); ctx = 0; return true; }
		public boolean	is_running()	{ return plist.running; }
		public boolean  initialized() 	{ return ctx != 0; }	// why this function? 
		public boolean	is_paused()		{ return plist.paused; }
		public int		get_cur_mode()	{ return plist.cur_mode; }
		public String	get_cur_dir()	{ return plist.dir; }
		public int		get_cur_pos()	{ return plist.cur_pos; }
		public int		get_cur_seconds()		{ return plist.getCurPosition(); }
		public int		get_track_duration()		{ return plist.getDuration(); }
		public int		get_cur_track_start() { return curTrackStart; }
		public int		get_cur_track_len() { return curTrackLen; }
		public String  	get_cur_track_source()	{ try { return plist.files[plist.cur_pos]; } catch(Exception e) {return null;} }
		public String  	get_cur_track_name()	{ try { return plist.names[plist.cur_pos]; } catch(Exception e) {return null;} }
		public void		set_driver_mode(int m) 	{ plist.driver_mode = m; }
		public void 	registerCallback(IAndLessSrvCallback cb)   { if(cb != null) cBacks.register(cb); };
		public void 	unregisterCallback(IAndLessSrvCallback cb) { if(cb != null) cBacks.unregister(cb); };
	    public int []	get_cue_from_flac(String file) {return  extractFlacCUE(file); };
	};
	
	///////////////////////////////////////////////////////
	///////////////////// Overrides ///////////////////////
	
	@Override
	public IBinder onBind(Intent intent) {
		log_msg("onBind()");	
		return binder;
	}
	
	@Override
	public void onCreate() {
		   	super.onCreate();
		   	log_msg("onCreate()");
           	TelephonyManager tmgr = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
	        tmgr.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
	        if(wakeLock == null) {
	        	PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
	        	wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
	        	wakeLock.setReferenceCounted(false);
	        }
	        plist = new AndLessSrv.playlist();
	        if(nm == null) nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
	        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
	}
	
	@Override
	public void onDestroy() {
			log_msg("onDestroy()");
		//	cBacks.kill();
			if(plist != null && plist.running) plist.stop();
			if(ctx != 0) audioExit(ctx);
			TelephonyManager tmgr = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
	        tmgr.listen(phoneStateListener, 0);
	        if(wakeLock != null && wakeLock.isHeld()) wakeLock.release();
	        if(nm != null) nm.cancel(NOTIFY_ID);
	}	 
	
	@Override
	public boolean onUnbind(Intent intent) {
		log_msg("onUnbind()");
	//	if(nm != null) nm.cancel(NOTIFY_ID);
		return super.onUnbind(intent);
	}

	////////////////////////////////////////////////////////
	//////// Pause when the phone rings, and resume after the call

	private PhoneStateListener phoneStateListener = new PhoneStateListener() {
	     private boolean needResume = false;
		 @Override
	     public void onCallStateChanged(int state, String incomingNumber) {
	            if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
	            	if(plist != null && plist.running && !plist.paused) {
	            		plist.pause();	
	            		needResume = true;
	            	}
	            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
	            	if(needResume) {
	            		if(plist != null) plist.resume();
	            		needResume = false;
	            	}
	            }
	     }
	};

	////////////////////////////////////////////////////
	//////////  We need read-write access to this device

	final private String MSM_DEVICE = "/dev/msm_pcm_out";
	
	public boolean checkSetDevicePermissions() {
        java.lang.Process process = null;
        DataOutputStream os = null;
                try {
                	File f = new File(MSM_DEVICE);
                		if(f.canRead() && f.canWrite() /* && f1.canRead() && f1.canWrite() */) {
                			log_msg("neednt set device permissions");
                			return true;
                		}
                		log_msg("attempting to set device permissions");
                		process = Runtime.getRuntime().exec("su");
                        os = new DataOutputStream(process.getOutputStream());
                        os.flush();
                        os.writeBytes("chmod 0666 " + MSM_DEVICE + "\n");
                        os.flush();
                        os.writeBytes("exit\n");
                        os.flush();
                        process.waitFor();
                } catch (Exception e) { 
                	log_err("exception while setting device permissions: " + e.toString());	
                	return false; 
                } finally {
                        try {
                                if(os != null) os.close();
                                process.destroy();
                        } catch (Exception e) { }
                }
                return true;
	}

}
