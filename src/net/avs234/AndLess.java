package net.avs234;	

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.RadioGroup.OnCheckedChangeListener;

import net.avs234.iconifiedlist.IconifiedText;
import net.avs234.iconifiedlist.IconifiedTextListAdapter;

public class AndLess extends Activity implements Comparator<File> {
	
    	// Current directory **OR** current cue/playlist file
    	private File cur_path = null;

    	// File/dir names together with their icons displayed in the list widget 
    	private ArrayList<IconifiedText> directoryEntries = new ArrayList<IconifiedText>();

    	// Full paths to files in current dir/playlist/cue  
    	private ArrayList<String> files = new ArrayList<String>();
    	
    	// Track names in cue file  
    	private ArrayList<String> track_names = new ArrayList<String>();
    	
    	// Track start times (seconds) in cue file	
    	private ArrayList<Integer> start_times = new ArrayList<Integer>();
    	
    	// Index of 1st audio file in directoryEntries list (below directories, cues, and playlists)
    	private int first_file_pos;

    	// Changed to true in playlists/settings dialogs
    	private boolean playlist_changed = false;
    	
    	private void log_msg(String msg) {
    		Log.i(getClass().getSimpleName(), msg);
    	}
    	private void log_err(String msg) {
    		Log.e(getClass().getSimpleName(), msg);
    	}

  		
    	// UI elements defined in layout xml file.
		private Button buttUp, buttPause, buttPrev, buttNext, buttVMinus, buttVPlus, buttQuit;
    	private ListView fileList;
    	private ProgressBar pBar;
    	private String curWindowTitle = null; 	
    	
        // Interface which is an entry point to server functions. Returned upon connection to the server. 
        private IAndLessSrv srv = null;

    	// Callback for server to report track/state changes.  Invokes the above handler to set window title. 
    	private IAndLessSrvCallback cBack = new IAndLessSrvCallback.Stub() { 
    		public void playItemChanged(boolean error, String name) {
    			log_msg(String.format("track name changed to %s", name));
    			Message msg = new Message();
    			Bundle data = new Bundle();
    			data.putString("filename", name);
    			data.putBoolean("error", error);
    			msg.setData(data);
    			hdl.sendMessage(msg);
    		}
    		public void errorReported(String name) {
    			log_msg(String.format("error \"%s\" received", name));
    			Message msg = new Message();
    			Bundle data = new Bundle();
    			data.putString("errormsg", name);
    			msg.setData(data);
    			hdl.sendMessage(msg);
    		}
    	};
    	
    	IBinder.DeathRecipient bdeath = new IBinder.DeathRecipient() {
    		public void binderDied() {
				log_err("Binder died, trying to reconnect");
			//	unbindService(conn);
				conn = new_connection();
				Intent intie = new Intent();
				intie.setClassName("net.avs234", "net.avs234.AndLessSrv");
                if(!stopService(intie)) log_err("service not stopped");
                if(startService(intie)== null) log_msg("service not started");
                else log_msg("started service");
                if(!bindService(intie, conn,0)) log_err("cannot bind service");
                else log_msg("service bound");
			}
    	};
    	
    	// On connection, obtain the service interface and setup screen according to current server state 
    	private ServiceConnection conn = null;
    	
    	ServiceConnection new_connection() {
    	 return new ServiceConnection() {
    		public void onServiceConnected(ComponentName cn,IBinder obj) {
    			srv = IAndLessSrv.Stub.asInterface(obj);
    			
    			if(srv == null) {
    				log_err("failed to get service interface"); 
    				errExit(R.string.strErrSrvIf);
    				return;
    			}
    			try{
    			//	if(!srv.initialized()) {
        		//		log_err("Server failed to initialize, exiting");
        		//		errExit(getString(R.string.strSrvInitFail));
    			//	}
        			obj.linkToDeath(bdeath,0);
    				String s = srv.get_cur_dir();
    				if(s != null) {
    					File f = new File(s);
    					if(f.exists() && (f.isDirectory() || hasPlistExt(s) || hasCueExt(s))) {
    						if(setAdapter(f)) { 
    							log_msg("restored previous playlist");
    							int i = srv.get_cur_pos();
    							if(i >= 0 && first_file_pos + i < directoryEntries.size()) {
    								fileList.setSelection(first_file_pos+i);
    							}
    							if(srv.is_paused()) { 
    								cBack.playItemChanged(true,getString(R.string.strPaused));
    								buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_play));
    							} else if(srv.is_running()) cBack.playItemChanged(false,directoryEntries.get(first_file_pos+i).getText());
    							  else cBack.playItemChanged(true,getString(R.string.strStopped));
    						} else s = null;
    					}
    				}	
    				if(s == null) {
    					if(prefs.last_path == null || !(cur_path = new File(prefs.last_path)).exists()) {
    		            	cur_path = Environment.getExternalStorageDirectory();
    		            }	
    		            if(!setAdapter(cur_path)) {
    		            	log_err("cannot set default adapter!!!" + cur_path);
    		            	if(!setAdapter(new File("/"))) errExit(R.string.strCantSetup);
    		            }
    		            fileList.setSelection(0);
    		            cBack.playItemChanged(true,getString(R.string.strStopped));
    				}
    				srv.registerCallback(cBack);
    			} catch(RemoteException e) {log_msg("remote exception in onServiceConnected: " + e.toString()); }
    		//	Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
    		}
    		public void onServiceDisconnected(ComponentName cn) { 
    			srv = null;
    		}
    	   }; 
    	}
    	
    	// Helper class to improve interface responsiveness. When the user clicks a button, it executes 
    	// the corresponding server command in background, and setups the UI upon its completion.   
    	private class SendSrvCmd extends AsyncTask<Integer,Void,Integer> {
    		public static final int cmd_pause = 1, cmd_prev = 2, cmd_next = 3, cmd_vol_up = 4, cmd_vol_down = 5;
    		private final int dont_change_btn = 0, change_to_pause_btn = 1, change_to_play_btn = 2;
    		private String now_playing = null;
    		protected Integer doInBackground(Integer... func) {
    			try {
    				switch(func[0]) {
    					case cmd_pause:
    						if(srv.is_paused()) {
    	    					if(srv.resume() && srv.is_running()) {
    	    						now_playing = curWindowTitle;
    	    						if(now_playing != null) return change_to_pause_btn;
    	    						now_playing = srv.get_cur_track_name();
    	    						if(now_playing != null) return change_to_pause_btn;
    	    						now_playing = srv.get_cur_track_source();
    	    						if(now_playing != null) {
    	    							int i = now_playing.lastIndexOf('/'); 
    	    							if(i >= 0) now_playing = now_playing.substring(i+1);
    	    						}
    	    						return change_to_pause_btn;
    	    					}
    	    				} else if(srv.pause()) return change_to_play_btn;
    						break;
    					case cmd_prev:
    						srv.set_driver_mode(prefs.driver_mode);
    						srv.play_prev(); break;
    	    			case cmd_next:		
    	    				srv.set_driver_mode(prefs.driver_mode);
    	    				srv.play_next(); break;
    	    			case cmd_vol_up:	
    						srv.inc_vol(); break;
    					case cmd_vol_down:	
							srv.dec_vol(); break;    						
    				}
    			} catch (RemoteException e) {log_err("remote exception in SendSrvCmd (" + func[0] + "): " + e.toString()); }
    			return dont_change_btn;
    		}
    		protected void onPostExecute(Integer result) {
    			switch(result) {
    				case change_to_pause_btn:
    					if(now_playing != null) getWindow().setTitle(now_playing);
    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_pause));
    					break;
    				case change_to_play_btn:    					
    					getWindow().setTitle(getString(R.string.strPaused));
    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_play));
    					break;
    			}
    		}
    	}
    	
/*    	class RestoreButton implements Runnable {
       		private int init_res;
       		private Button button;
       		RestoreButton(Button b, int res) { button = b; init_res = res; }
       		public void run() {
       			button.setBackgroundDrawable(getResources().getDrawable(init_res));
			}
       	}
		// Before its replacement with v.startAnimation(...) it was used as:
    	//	 v.setBackgroundDrawable(getResources().getDrawable(R.drawable.green_go_prev_32));
    	//	 v.scheduleDrawable(v.getBackground(), new RestoreButton((Button) v,R.drawable.go_prev_32), SystemClock.uptimeMillis()+delay);
*/    	
    	private boolean samsung = false; 
    	
    	View.OnClickListener onButtPause = new OnClickListener() {
    		private String now_playing = null;
    		public void onClick(View v) { 
    			if(samsung) {
        			try {
  						if(srv == null) errExit(R.string.strErrSrvZero);
        				if(srv.is_paused()) {
  							if(srv.resume() && srv.is_running()) {
    						now_playing = curWindowTitle;
    						  if(now_playing == null) {
    							now_playing = srv.get_cur_track_name();
    							if(now_playing == null) {
    								now_playing = srv.get_cur_track_source();
    								if(now_playing == null) {
    									int i = now_playing.lastIndexOf('/'); 
    									if(i >= 0) now_playing = now_playing.substring(i+1);
    								}
    							}
    						  }
  							}  
  	    					if(now_playing != null) getWindow().setTitle(now_playing);
  	    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_pause));
  						} else if(srv.pause()) {
  	    					getWindow().setTitle(getString(R.string.strPaused));
  	    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_play));
  						}
 					} catch (RemoteException e) {
						log_err("exception in pause button handler");
					}
    			} else new SendSrvCmd().execute(SendSrvCmd.cmd_pause); 
    		}
    	};	

    	
    	private void enableVolumeButtons(boolean enable) {
    		if(buttVPlus == null || buttVMinus == null) return;
    		buttVPlus.setEnabled(enable);
    		buttVMinus.setEnabled(enable);
    		buttVPlus.setBackgroundDrawable(getResources().getDrawable(enable ? R.drawable.vol_plus : R.drawable.green_vol_plus));
    		buttVMinus.setBackgroundDrawable(getResources().getDrawable(enable ? R.drawable.vol_minus : R.drawable.green_vol_minus));
    	}
    	
    	View.OnClickListener onButtPrev= new OnClickListener() {
    		public void onClick(View v) {
    			v.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(),  R.anim.blink));
    			if(samsung) {
        			try {
        				if(srv == null) errExit(R.string.strErrSrvZero);
        				srv.set_driver_mode(prefs.driver_mode);
	    				srv.play_prev();
					} catch (RemoteException e) {
						log_err("exception in prev button handler");
					}
    			} else new SendSrvCmd().execute(SendSrvCmd.cmd_prev); 
    		}	
    	};
    	View.OnClickListener onButtNext = new OnClickListener() {
        	public void onClick(View v) { 
        		v.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(),  R.anim.blink));
        		if(samsung) {
        			try {
        				if(srv == null) errExit(R.string.strErrSrvZero);
        				srv.set_driver_mode(prefs.driver_mode);
	    				srv.play_next();
					} catch (RemoteException e) {
						log_err("exception in next button handler");
					}
        		} else new SendSrvCmd().execute(SendSrvCmd.cmd_next); 
        	}	
        };
        View.OnClickListener onButtVPlus = new OnClickListener() {
        	public void onClick(View v) { 
        		v.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(),  R.anim.blink));
        		if(samsung) {
        			try {
        				if(srv == null) errExit(R.string.strErrSrvZero);
        				srv.inc_vol();
					} catch (RemoteException e) {
						log_err("exception in plus button handler");
					}
        			
        		} else new SendSrvCmd().execute(SendSrvCmd.cmd_vol_up); 
        	}	
        };
        View.OnClickListener onButtVMinus = new OnClickListener() {
        	public void onClick(View v) { 
        		v.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(),  R.anim.blink));
        		if(samsung) {
        			try {
        				if(srv == null) errExit(R.string.strErrSrvZero);
        				srv.dec_vol();
					} catch (RemoteException e) {
						log_err("exception in minus button handler");
					}
        		} else new SendSrvCmd().execute(SendSrvCmd.cmd_vol_down); 
        	}	
        };
    	
        View.OnClickListener onButtQuit = new OnClickListener() {
    		public void onClick(View v) {
    			v.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(),  R.anim.blink));
    			prefs.save();
    			try {
    				if(srv != null) {
						if(srv.is_running()) saveBook();
    					srv.shutdown();	
    				}
    			} catch (Exception e) {
    				log_err("exception while shutting down");
    			}
    			
    			if(conn != null) {
            		log_msg("unbinding service");
            		unbindService(conn);
            		conn = null;
            	}
        	    Intent intie = new Intent();
                intie.setClassName("net.avs234", "net.avs234.AndLessSrv");
                if(!stopService(intie)) log_err("service not stopped");	
                else log_msg("service stopped");
                finish();
	    		android.os.Process.killProcess(android.os.Process.myPid());
    		}
    	};
  	
    	View.OnClickListener onButtUp = new OnClickListener() {
    		public void onClick(View v) {
    			if(cur_path == null) return;
    			v.startAnimation(AnimationUtils.loadAnimation(getApplicationContext(),  R.anim.blink));
    			File path = cur_path.getParentFile();
    			if(path != null) {
    				int k = cur_path.toString().lastIndexOf('/');
    				String last_path = cur_path.toString().substring(k+1);
    				if(setAdapter(path)) { 
    					for(k=0; k < directoryEntries.size(); k++)
    						if(directoryEntries.get(k).getText().compareTo(last_path)==0) break;
    					fileList.setSelection(k);
    				} else log_err("cannot restore in onButtUp()");
    			}
   			
				
    		}
    	};

    	// Load the server playlist with contents of arrays, and play starting from the k-th item @ time start. 
    	
    	private boolean playContents(String fpath, ArrayList<String> filez, ArrayList<String> namez, 
    				ArrayList<Integer> timez, int k, int start) {
    		try {
    			if(!srv.init_playlist(fpath,filez.size())) {
    				log_err("failed to initialize new playlist on server"); 
    				Toast.makeText(getApplicationContext(), R.string.strSrvFail, Toast.LENGTH_SHORT).show();
    				return false;
    			}
    			for(int i = 0; i < filez.size(); i++) 
    				if(!srv.add_to_playlist(filez.get(i), (namez != null) ? namez.get(i) : null, (namez != null) ? timez.get(i) : 0,i)) {
        					log_err("failed to add a file to server playlist"); 
        					Toast.makeText(getApplicationContext(), R.string.strSrvFail, Toast.LENGTH_SHORT).show();
        					return false;
    				}
    			srv.set_driver_mode(prefs.driver_mode);
    			if(!srv.play(k,start)) {
    				Toast.makeText(getApplicationContext(), R.string.strSrvFail, Toast.LENGTH_SHORT).show();
    				log_err("failed to start playing <contents>"); 
    				return false;
    			}
    			buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_pause));
    			return true;
			} catch (Exception e) { 
				log_err("exception in playContents: " + e.toString()); 
				e.printStackTrace();
				return false;
			}
    		
    	}
    	// Save the last played file in format "file:pos:time"
    	void saveBook() {
    		try {
        		int i;
    			File book_file;
        		String s =  srv.get_cur_dir();

        		if (hasPlistExt(s) || hasCueExt(s)) {
        			i = s.lastIndexOf('/');
    				book_file = new File(s.substring(0, i)+"/resume.bmark");
    			} else {
    				book_file = new File(s +"/resume.bmark");
    				s = srv.get_cur_track_source();
    			}
    			if(book_file.exists()) book_file.delete();
    			BufferedWriter writer = new BufferedWriter(new FileWriter(book_file, false), 8192);
    			String g = s + String.format(":%d:%d", 
    					srv.get_cur_seconds() - srv.get_cur_track_start(), srv.get_cur_pos());   
    			writer.write(g);
   			   	writer.close();
   			   	log_msg("SAVING BOOK: " + book_file.toString() + ": " + g);
    		} catch (Exception e) { 
				log_err("exception in saveBook: " + e.toString()); 
			}
    	}
    	
    	////////////  Change to the selected directory/cue/playlist, or play starting from the selected track 
    	AdapterView.OnItemClickListener selItem = new OnItemClickListener() {

    		public void onItemClick(AdapterView<?> a, View v, int i,long k) {

				if((int) k >= files.size()) {
					log_err("cilcked item out of range!");
					return;
				}
				
				File f = new File(files.get((int)k));

				if(f.exists()) {
					if(f.toString().endsWith(bmark_ext)) {
			    		try {
			    			if(srv.is_running()) saveBook();
			    			BufferedReader reader = new BufferedReader(new FileReader(f), 8192);
			    			String line = reader.readLine();
			    			reader.close();
			    			int end = line.indexOf(":");
			    			if(end < 1) throw new NullPointerException();
			    			String fname = line.substring(0, end);
			    			File ff = new File(fname);
			    			if(!ff.exists()) throw new NullPointerException();
			    			int start = end + 1;
			    			end = line.substring(start).indexOf(":") + start;
			    			if(end < 0) throw new NullPointerException();
	    	        		int seconds = (Integer.valueOf(line.substring(start, end))).intValue();
	    	        		int track = 0;
	    					String cc = srv.get_cur_dir();
	    	        			    					   	        		
	    	        		if(hasAudioExt(ff)) {
	    	        			log_msg("BOOK: " + fname + " @" + seconds + " cc=" + cc + " cp=" + cur_path.toString());
	    	        			for(start = first_file_pos; start < files.size(); start++) {
	    							if(files.get(start).compareTo(fname)==0) break;	
	    						}
	    	        			track = start;
	    	        			if(track >= files.size()) throw new NullPointerException();
	    	        			if(cc == null || cur_path.toString().compareTo(cc) != 0 || playlist_changed) {
	        						playlist_changed = false;
	        						ArrayList<String> filly = new ArrayList<String>();
	        						for(int j = first_file_pos; j < files.size(); j++) filly.add(files.get(j));
	        						playContents(cur_path.toString(),filly,null,null,start - first_file_pos,seconds);
	        						return;
	        					}	    	        			
	    	        		} else if(hasPlistExt(ff) || hasCueExt(ff)) {
	    	        			track = (Integer.valueOf(line.substring(end+1))).intValue();
	    	        			log_msg("BOOK: " + fname + " ["+ track +"] @" + seconds + " cc=" + cc + " cp=" + cur_path.toString());
	    	        			if(!setAdapter(ff)) {
	        						log_err("error setting adapter for " + f.toString());
	        						return;
	        					}
	        					if(cc == null || cur_path.toString().compareTo(cc) != 0 || playlist_changed) {
	        						if(hasCueExt(ff)) 
	        							playContents(ff.toString(),files,track_names,start_times,track - first_file_pos,seconds);
	        						else if(hasPlistExt(ff)) 
	        							playContents(ff.toString(),files,null,null,track - first_file_pos,seconds);
	        						return;
	        					}
	    	        		}
	    	        		srv.set_driver_mode(prefs.driver_mode);
    						if(!srv.play(track -first_file_pos,seconds)) {
    							Toast.makeText(getApplicationContext(), R.string.strSrvFail, Toast.LENGTH_SHORT).show();
    							log_err("failed to start playing <bookmarked file>"); 
    						}
    						buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_pause));
    						return;
			    		} catch (Exception e) { 
							log_err("exception while processing bookmark file!");
						}	
	    				Toast.makeText(getApplicationContext(), R.string.strBadBook, Toast.LENGTH_SHORT).show();
	    				return;
					}
					if(k < first_file_pos) {	// Directory, cue or playlist was clicked in the list 
    					if(!setAdapter(f)) {
    						log_err("error setting adapter for " + f.toString());
    					}
    				} else try {				// Regular file, or cue track. first_file_pos is always 0 for playlists and cues.
						if(srv.is_running()) saveBook();
    					String cc = srv.get_cur_dir();
    					String cdr = cur_path.toString();
    					log_msg(String.format("Attempting to play file %d in %s",(int)k - first_file_pos,cdr));
    					if(cc == null || cdr.compareTo(cc) != 0 || playlist_changed) {
    						playlist_changed = false;
    						if(cur_path.isDirectory()) {
    							ArrayList<String> filly = new ArrayList<String>();
    							for(int j = first_file_pos; j < files.size(); j++) filly.add(files.get(j));
    							playContents(cdr,filly,null,null,(int)k  - first_file_pos,0);
    						} else if(hasCueExt(cdr)) {
    							playContents(cdr,files,track_names,start_times,(int)k - first_file_pos,0);
    						} else if(hasPlistExt(cdr)) {
    							playContents(cdr,files,null,null,(int)k - first_file_pos,0);
    						}
    					} else {
    						srv.set_driver_mode(prefs.driver_mode);
    						if(!srv.play((int)k - first_file_pos,0)) {
    							Toast.makeText(getApplicationContext(), R.string.strSrvFail, Toast.LENGTH_SHORT).show();
    							log_err("failed to start playing <single file>"); 
    						}
    						buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.img_pause));
    					}
    				} catch (Exception e) { 
    					e.printStackTrace();
    					log_err("exception in selItem: " + e.toString()); 
    				}
    			} else log_err("Attempt to play non-existing file " + f.toString());
    		}
    	};

    	// If a playlist or cue was long-pressed, its contents are sent to server, and playback  
    	// starts immediately without changing into that playlist. 

    	private int cur_longpressed = 0;
    	AdapterView.OnItemLongClickListener pressItem = new OnItemLongClickListener() {
			public boolean onItemLongClick(AdapterView<?> a, View v, int i, long k) {
				
				if((int) k >= files.size()) {
					log_err("long-pressed item out of range!");
					return false;
				}
				File f = new File(files.get((int)k));
				cur_longpressed = (int) k;
				if(!f.exists()) {
					log_err("non-existing item long-pressed in the list!");
					return false;
				}
				if(hasPlistExt(f) || hasCueExt(f)) {
					if(!playPath(f)) {
						log_err("error handling long press for " + f.toString());
						return false;
					}
					return true;
				}
				if(hasPlistExt(cur_path)) {	// edit playlist
					if(prefs.shuffle) {
	   					Toast.makeText(getApplicationContext(), R.string.strTurnOffShuffle, Toast.LENGTH_SHORT).show();
	   					return false;
	   				}
					showDialog(EDIT_PLAYLIST_DLG);
					return true;
				}
				if(f.isDirectory() || hasAudioExt(f)) {	// add to playlist
					showDialog(ADD_PLAYLIST_DLG);
					return true;
				}
				log_err("unknown item long-pressed!");
				return false;
			}
    	};

    	////////////////////////////////////////////////////////////////
    	////////////////////////////// Handlers ////////////////////////
    	////////////////////////////////////////////////////////////////
    	
		private class TrackTimeUpdater {

			private String track_name;
			private boolean init_completed = false;
			private boolean need_update = false;

			private Timer timer;
			private UpdaterTask timer_task;
			private Handler progressUpdate = new Handler();
			
			private final int first_delay = 500;
			private final int update_period = 1500;
			
			private class UpdaterTask extends TimerTask {
				public void run() {
					if(track_name == null) {
						shutdown();
						return;
					}
					if(init_completed) {
						progressUpdate.post(new Runnable() {
							public void run() {
								if(srv == null) return;
								try {
									if(!srv.is_running() || srv.is_paused()) return;
					   				if(need_update) {		// track_time was unknown at init time
					   				//	int track_time = AndLessSrv.curTrackLen;
					   					int track_time = srv.get_cur_track_len();
					   					if(track_time <= 0) {
					   						track_time = srv.get_track_duration();
					   						if(track_time <=0) return;
					   					}	
					       				curWindowTitle = (track_time < 3600) ? String.format("[%d:%02d] %s", track_time/60, track_time % 60, track_name) 
					       					:	String.format("[%d:%02d:%02d] %s", track_time/3600, (track_time % 3600)/60, track_time % 60, track_name);
					       				getWindow().setTitle(curWindowTitle);
					       				pBar.setMax(track_time);
					   					need_update = false;
					   				}
								//	pBar.setProgress(srv.get_cur_seconds() - AndLessSrv.curTrackStart);
					   				pBar.setProgress(srv.get_cur_seconds() - srv.get_cur_track_start());
								} catch (Exception e) { 
									log_err("exception 1 in progress update handler: " + e.toString()); 
								}
							}
						});
						return;
					}
					progressUpdate.post(new Runnable() {	// initialize
						public void run() {
							if(srv == null) return;
							try {
							//	int track_time = AndLessSrv.curTrackLen;
								int track_time = srv.get_cur_track_len();
								need_update = false;
								if(track_time <= 0) {
									log_msg("progressUpdate(): fishy track_time " + track_time);
									track_time = srv.get_track_duration();
									if(track_time <=0) need_update = true;
								}
								curWindowTitle = (track_time < 3600) ? String.format("[%d:%02d] %s", track_time/60, track_time % 60, track_name) 
									:	String.format("[%d:%02d:%02d] %s", track_time/3600, (track_time % 3600)/60, track_time % 60, track_name);
								getWindow().setTitle(curWindowTitle);
								pBar.setMax(track_time);
							} catch (Exception e) { 
								log_err("exception 2 in progress update handler: " + e.toString()); 
							}
						}
					});
			    	init_completed = true;
				}
			}	
			public void shutdown() {
				if(timer_task != null) timer_task.cancel();
				if(timer != null) timer.cancel();
				timer = null; timer_task = null;
				track_name = null; init_completed = false;
			}
			private void reset() {
				shutdown();
				timer_task = new UpdaterTask();
				timer = new Timer();
			}
			
			public void start(String s) {
				reset();
				track_name = new String(s);
				timer.schedule(timer_task, first_delay, update_period);
			}
		}
		
		TrackTimeUpdater ttu = new TrackTimeUpdater();
    	
        Handler hdl = new Handler() {
      	  @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                Bundle bb = msg.getData();
                if(bb == null) return;
                String curfile = bb.getString("filename");
                if(curfile != null) {
              	  boolean error = bb.getBoolean("error");
              	  if(!error) {	
              		  // normal track, need to setup track time/progress update stuff
              		  if(pBar != null) pBar.setProgress(0);
              		  if(!samsung) ttu.start(curfile);
              		  else getWindow().setTitle(curfile);
              		  return;
              	  } else {
              	//	  if(pBar != null) pBar.setProgress(0);
              		  getWindow().setTitle(curfile);
              		if(!samsung) ttu.shutdown();
              		  return;
              	  }
                }
                if(!samsung) ttu.shutdown();
        		if(pBar != null) pBar.setProgress(0);
                curfile = bb.getString("errormsg");
                if(curfile == null) return;
                showMsg(curfile);
      	  }
      };
    	      
    	////////////////////////////////////////////////////////////////
    	///////////////////////// Entry point //////////////////////////
    	////////////////////////////////////////////////////////////////

    	@Override
        public void onCreate(Bundle savedInstanceState) {
    		
    		super.onCreate(savedInstanceState);

    		prefs = new Prefs();
            prefs.load();

            if(prefs.theme == 1) setTheme(android.R.style.Theme_Light);
            else setTheme(android.R.style.Theme_Black);
            
            if(prefs.layout == 1) setContentView(R.layout.lower);            
            else setContentView(R.layout.main);
            
            setContent();

			if(prefs.theme == 0) fileList.setBackgroundResource(android.R.color.background_dark);
			else fileList.setBackgroundResource(android.R.color.background_light); 
            
            buttPause.setEnabled(true);

            Intent intie = new Intent();
            intie.setClassName("net.avs234", "net.avs234.AndLessSrv");
            
            if(startService(intie)== null) log_msg("service not started");
            else log_msg("started service");

            if(conn == null) conn = new_connection();
            
            if(!bindService(intie, conn,0)) log_err("cannot bind service");
            else log_msg("service bound");
            if((new Build()).DEVICE.compareTo("GT-I5700") == 0 && (new Build.VERSION()).SDK.compareTo("7") == 0) samsung = true;
    	}

    	@Override
        public void onDestroy() {
    		super.onDestroy();
    		prefs.save();
    		if(srv != null) {
    			try {
    				srv.unregisterCallback(cBack);
    			
    			} catch (RemoteException e) {
    				log_err("remote exception in onDestroy(): " + e.toString());
    			} 
    		}
    		if(conn != null) {
    			log_msg("unbinding service");
    			unbindService(conn);
    			conn = null;
    		}
    	}
      	
    	@Override
    	public void  onConfigurationChanged  (Configuration  newConfig) {
    		super.onConfigurationChanged(newConfig);
    	}

    	// Save/restore user preferences.
    	
        class Prefs {
        	
        	public static final String PREFS_NAME = "prefs_avs234";
        	public int theme;
        	public int layout;
        	public float fsize;
        	public String last_path;
        	public String plist_path;
        	public String plist_name;
        	public boolean shuffle;
        	public int driver_mode;
        	public void load() {
        		SharedPreferences shpr = getSharedPreferences(PREFS_NAME, 0);
                theme = shpr.getInt("theme", 0);		fsize = shpr.getFloat("fsize", 16.0f);
                layout = shpr.getInt("layout", 0);		shuffle = shpr.getBoolean("shuffle", false);		
                driver_mode = shpr.getInt("driver_mode", AndLessSrv.MODE_LIBMEDIA);
                last_path = shpr.getString("last_path", null);
                plist_path = shpr.getString("plist_path", Environment.getExternalStorageDirectory().toString());
                plist_name = shpr.getString("plist_name", "Favorites");
        	}
        
        	public void save() {
        	  	SharedPreferences shpr = getSharedPreferences(PREFS_NAME, 0);
        	  	SharedPreferences.Editor editor = shpr.edit();
        	  	editor.putInt("theme", theme);			editor.putFloat("fsize", fsize);
        	  	editor.putInt("layout", layout);		editor.putBoolean("shuffle", shuffle);
        	  	editor.putInt("driver_mode", driver_mode);
        	  	if(cur_path != null) editor.putString("last_path", cur_path.toString());
        	  	if(plist_path != null) editor.putString("plist_path", plist_path);
        	  	if(plist_name != null) editor.putString("plist_name", plist_name);
        	  	if(!editor.commit()) showMsg(getString(R.string.strErrPrefs));
        	}
        }
        
        public static Prefs prefs;
    	
    	////////////////////////////////////////////////////////////////
    	///////////////////// Menus and dialogs ////////////////////////
    	////////////////////////////////////////////////////////////////
  	
    	@Override
    	public boolean onCreateOptionsMenu(Menu menu) {
    	    MenuInflater inflater = getMenuInflater();
    	    inflater.inflate(R.menu.mm, menu);
    	    return true;

    	}
    	
    	private void setContent() {
            setRequestedOrientation(1);
            buttUp = (Button) findViewById(R.id.ButtonUp);
            buttPause = (Button) findViewById(R.id.ButtonPause);
            buttPrev = (Button) findViewById(R.id.ButtonPrev);
            buttNext = (Button) findViewById(R.id.ButtonNext);
            buttVMinus = (Button) findViewById(R.id.ButtonVMinus);
            buttVPlus = (Button) findViewById(R.id.ButtonVPlus);
            buttQuit = (Button) findViewById(R.id.ButtonQuit);
            fileList = (ListView) findViewById(R.id.FileList);
            pBar = (ProgressBar) findViewById(R.id.PBar);
            buttUp.setOnClickListener(onButtUp);
            buttPause.setOnClickListener(onButtPause);
            buttPrev.setOnClickListener(onButtPrev);
            buttNext.setOnClickListener(onButtNext);
            buttVMinus.setOnClickListener(onButtVMinus);
            buttVPlus.setOnClickListener(onButtVPlus);
            buttQuit.setOnClickListener(onButtQuit);
            fileList.setOnItemClickListener(selItem);
            fileList.setOnItemLongClickListener(pressItem);
            enableVolumeButtons(prefs.driver_mode == AndLessSrv.MODE_DIRECT);
            try {
				if(srv != null && srv.is_running()) //pBar.setMax(AndLessSrv.curTrackLen);
					pBar.setMax(srv.get_cur_track_len());
            } catch (RemoteException e) {
				log_err("remote exception in setContent()");
			}
    	}
      	    	    	
      	static final int SETTINGS_DLG = 0;
      	static final int ADD_PLAYLIST_DLG = 1;
      	static final int EDIT_PLAYLIST_DLG = 2;
      	
      	private RadioGroup rl, rt, rf;
      	private CheckBox chk, chkm;
      	
      	private OnCheckedChangeListener ochl = new  OnCheckedChangeListener() {
			public void onCheckedChanged(RadioGroup group, int id) {
				if(group == rl) prefs.layout = (id == R.id.NormalLayout) ? 0 : 1;
				else if(group == rt) prefs.theme = (id == R.id.NormalColors) ? 0 : 1;
				else switch(id) {
						case R.id.Font12: prefs.fsize = 12.0f; break;
						case R.id.Font16: prefs.fsize = 16.0f; break;
						case R.id.Font20: prefs.fsize = 20.0f; break;
						case R.id.Font24: prefs.fsize = 24.0f; break;
					 }; 
			}
		};

		View.OnClickListener oncl = new OnClickListener() {
    		public void onClick(View v) {  
    		
    		}
    	};
		
    	private boolean item_deleted = false;
    	private int track_longpressed;
    	private String curRowIs;
    	private String itemDeleted;
    	private Dialog thisDialog;
    	private CheckBox chb;
    	protected void  onPrepareDialog  (int id, Dialog  dialog) {
    		switch(id) {
    		   	case EDIT_PLAYLIST_DLG:
       				item_deleted = false;
       				track_longpressed = cur_longpressed;
//       				tvRow.setText(curRowIs + " " + (cur_longpressed + 1));
       				curRowIs = getString(R.string.strCurRow);
       				itemDeleted = getString(R.string.strDeleted);
       				thisDialog = dialog;
       				thisDialog.setTitle(curRowIs + " " + (track_longpressed + 1));
       				break;
    		   	case ADD_PLAYLIST_DLG:
    		   		thisDialog = dialog;
       				dialog.setTitle(getString(R.string.strAddPlist));
       				((EditText) dialog.findViewById(R.id.EditPlaylistPath)).setText(prefs.plist_path);
       				((EditText) dialog.findViewById(R.id.EditPlaylistName)).setText(prefs.plist_name);
       				chb = (CheckBox) dialog.findViewById(R.id.CheckRecursive);
       				chb.setChecked(false);
       				File f = new File(files.get(cur_longpressed));
       				if(!f.isDirectory()) chb.setEnabled(false);  else chb.setEnabled(true);
       				break;
    		   	default:
    				break;
    		}
    	}
    	
    	protected Dialog onCreateDialog(int id) {
			Dialog dialog;
			switch(id) {
   			 case SETTINGS_DLG:
   				
   				dialog = new Dialog(this);
    			dialog.setContentView(R.layout.settings);
   				dialog.setTitle(R.string.strSettings);
    		
   				rl = (RadioGroup) dialog.findViewById(R.id.RadioLayout);
   				rt = (RadioGroup) dialog.findViewById(R.id.RadioTheme);
   				rf = (RadioGroup) dialog.findViewById(R.id.RadioFont);
   				chk = (CheckBox) dialog.findViewById(R.id.CheckShuffle);
   				chkm = (CheckBox) dialog.findViewById(R.id.CheckMode);
   				
   				if(prefs.layout == 0) rl.check(R.id.NormalLayout); else rl.check(R.id.DownLayout);
   				if(prefs.theme == 0) rt.check(R.id.NormalColors);  else rt.check(R.id.InverseColors);
    	
   				if(prefs.fsize == 12.0f) rf.check(R.id.Font12);  		else if(prefs.fsize == 16.0f) rf.check(R.id.Font16);
   				else if(prefs.fsize == 20.0f) rf.check(R.id.Font20);	else rf.check(R.id.Font24);
    		
   				if(prefs.shuffle) chk.setChecked(true); else chk.setChecked(false); 
   				if(prefs.driver_mode == AndLessSrv.MODE_DIRECT) chkm.setChecked(true); else chkm.setChecked(false);
   				
   				rl.setOnCheckedChangeListener(ochl);
   				rt.setOnCheckedChangeListener(ochl);
   				rf.setOnCheckedChangeListener(ochl);
    		
   				dialog.setOnDismissListener(
   						new DialogInterface.OnDismissListener() {
   							public void onDismiss(DialogInterface df) { 
    					
   								if(chk.isChecked() != prefs.shuffle) playlist_changed = true;
   			
   								if(chk.isChecked()) prefs.shuffle = true;
   								else prefs.shuffle = false;
    					
   								if(chkm.isChecked()) prefs.driver_mode = AndLessSrv.MODE_DIRECT;
   								else prefs.driver_mode = AndLessSrv.MODE_LIBMEDIA;
   								
   								if(prefs.theme == 0) setTheme(android.R.style.Theme_Black);
   								else setTheme(android.R.style.Theme_Light);
    					
   								if(prefs.layout == 0) setContentView(R.layout.main);
   								else setContentView(R.layout.lower);
   								
   								setContent(); 

   								if(prefs.theme == 0) fileList.setBackgroundResource(android.R.color.background_dark);
   								else fileList.setBackgroundResource(android.R.color.background_light); 

   								if(!setAdapter(cur_path)) log_err("this cannot happen!");
   							}
   						}		
   				); 
   				return dialog;
   			 
   			 case ADD_PLAYLIST_DLG:
    				dialog = new Dialog(this);
       				dialog.setContentView(R.layout.add_plist);
       				
       				// Save
       				((Button) dialog.findViewById(R.id.ButtonSave)).setOnClickListener(new OnClickListener() {
       		    		public void onClick(View v) {
       		    			// save and reload playlist
       		    			String path = ((EditText) thisDialog.findViewById(R.id.EditPlaylistPath)).getText().toString();
       		    			String name = ((EditText) thisDialog.findViewById(R.id.EditPlaylistName)).getText().toString();
       		    			       		    			       		    			
       		    			String spath = new String(path);
       		    			String sname = new String(name);
       		    			if(!name.endsWith(plist_ext)) name += plist_ext;
       		    			if(!path.endsWith("/")) path += "/";
       		    			path = path + name;
       		    			File plist_file = new File(path);
       		    			File f = new File(files.get(cur_longpressed));
       		    			if(!f.exists()) {
       		    				Toast.makeText(getApplicationContext(), R.string.strInternalError, Toast.LENGTH_SHORT).show();
       		    				return;
       		    			}
       		    			ArrayList<String> filez = new ArrayList<String>();
       		    			if(f.isDirectory()) {
       		    				if(chb.isChecked()) {
       		    					filez = recurseDir(f);
       		    					if(filez.size() < 1) {
       		    						Toast.makeText(getApplicationContext(), R.string.strNoFiles, Toast.LENGTH_SHORT).show();
       		    						return;
       		    					}
       		    				} else {
       		    					parsed_dir d = parseDir(f);
       		    					if(d == null || d.flacs == 0) {
       		    						Toast.makeText(getApplicationContext(), R.string.strNoFiles, Toast.LENGTH_SHORT).show();
       		    						return;
       		    					}
       		    					for(int i = d.dirs + d.cues; i < d.filez.length; i++) filez.add(d.filez[i].toString());
       		    				}
       		    			} else filez.add(files.get(cur_longpressed).toString());
       		    			
       		    			try {
       		    			    boolean append = true;
       		    				if(!plist_file.exists()) {
       		    					plist_file.createNewFile(); append = false;
       		    				}
       		    				BufferedWriter writer = new BufferedWriter(new FileWriter(plist_file, append), 8192);
       		    			    for(int i = 0; i < filez.size(); i++) writer.write(filez.get(i) + "\n");
       		    			    writer.close();
           		    			prefs.plist_path = new String(spath);
           		    			prefs.plist_name = new String(sname);
       		    			} catch (Exception e) {
       		    				Toast.makeText(getApplicationContext(), R.string.strIOError, Toast.LENGTH_SHORT).show();
       		    				return;
       		    			}
       		    			dismissDialog(ADD_PLAYLIST_DLG);
       		    			thisDialog = null;
       		    		}
       		    	});
       				
       				// Cancel
       				((Button) dialog.findViewById(R.id.ButtonCancel)).setOnClickListener(new OnClickListener() {
       		    		public void onClick(View v) {
       		    			dismissDialog(ADD_PLAYLIST_DLG);
       		    			thisDialog = null;
       		    		}
       		    	});
       				
       				return dialog;
   			 
   			 case EDIT_PLAYLIST_DLG:
   				
   				dialog = new Dialog(this);
   				dialog.setContentView(R.layout.edit_plist);

   				// Move to bottom
   				((Button) dialog.findViewById(R.id.ButtonBottom)).setOnClickListener(new OnClickListener() {
   		    		public void onClick(View v) {
   		    			if(item_deleted) return;
   		    			track_longpressed = files.size() - 1;
   		    			thisDialog.setTitle(curRowIs + " " + (track_longpressed + 1));
   		    		}
   		    	});
   				
   				// Move down
   				((Button) dialog.findViewById(R.id.ButtonDown)).setOnClickListener(new OnClickListener() {
   		    		public void onClick(View v) {
   		    			if(item_deleted) return;
   		    			if(track_longpressed + 1 < files.size()) track_longpressed++;
   		    			thisDialog.setTitle(curRowIs + " " + (track_longpressed + 1));
   		    		}
   		    	});

   				// Move up
   				((Button) dialog.findViewById(R.id.ButtonUp)).setOnClickListener(new OnClickListener() {
   		    		public void onClick(View v) {
   		    			if(item_deleted) return;
   		    			if(track_longpressed > 0) track_longpressed--;
   		    			thisDialog.setTitle(curRowIs + " " + (track_longpressed + 1));
   		    		}
   		    	});

   				// Move to top
   				((Button) dialog.findViewById(R.id.ButtonTop)).setOnClickListener(new OnClickListener() {
   		    		public void onClick(View v) {
   		    			if(item_deleted) return;
   		    			track_longpressed = 0;
   		    			thisDialog.setTitle(curRowIs + " 1");
   		    		}
   		    	});

   				// Save
   				((Button) dialog.findViewById(R.id.ButtonSave)).setOnClickListener(new OnClickListener() {
   		    		public void onClick(View v) {
   		    			// save and reload playlist
   		    			if(cur_longpressed == track_longpressed && !item_deleted) {
   		    				Toast.makeText(getApplicationContext(), R.string.strNoChg, Toast.LENGTH_SHORT).show();
   		    			} else {
   		    				File cpath;
   		    				if(cur_path.toString().endsWith(plist_ext)) cpath = cur_path;
   		    				else cpath = new File(cur_path.toString() + plist_ext);
   		    				if(cpath.exists()) {
   		    					if(!cpath.delete()) {
   		    						Toast.makeText(getApplicationContext(), R.string.strCantRemovePlist, Toast.LENGTH_SHORT).show();
   		    						return;
   		    					}	
   		    				}
   		    				if(item_deleted) {
   		    					files.remove(cur_longpressed);
   		    				} else {
   		    					String moved = files.get(cur_longpressed);
   		    					files.remove(cur_longpressed);
   		    					files.add(track_longpressed, moved);
   		    				}
   		    				try {
   		    			    	BufferedWriter writer = new BufferedWriter(new FileWriter(cpath), 8192);
   		    			    	for(int i = 0; i < files.size(); i++) writer.write(files.get(i) + "\n");
   		    			    	writer.close();
   		    				} catch (Exception e) {
   		    			       	log_err("Exception while saving tracklist: " + e.toString());
   		    			    }
   		    					
   	   		    			setAdapterFromPlaylist(cpath);
   	   		    			if(item_deleted) fileList.setSelection(cur_longpressed >= files.size() ? files.size() - 1 : cur_longpressed);
   	   		    			else fileList.setSelection(cur_longpressed);
   	   		    			playlist_changed = true;
   		    				dismissDialog(EDIT_PLAYLIST_DLG);
   		    				thisDialog = null;
   		    			}	
   		    		}
   		    	});
   				
   				// Cancel
   				((Button) dialog.findViewById(R.id.ButtonCancel)).setOnClickListener(new OnClickListener() {
   		    		public void onClick(View v) {
   		    			dismissDialog(EDIT_PLAYLIST_DLG);
   		    			thisDialog = null;
   		    		}
   		    	});
   				
   				// Delete
   				((Button) dialog.findViewById(R.id.ButtonDelete)).setOnClickListener(new OnClickListener() {
   		    		public void onClick(View v) {
   		    			item_deleted = true;
   		    			thisDialog.setTitle(itemDeleted);
   		    		}
   		    	});
   				// tvRow = (TextView) dialog.findViewById(R.id.TextRow);
   				return dialog;
   			 default:
   				return null;
   			}
		
		}
    	
    	@Override
    	public boolean onOptionsItemSelected(MenuItem item) {
    		switch (item.getItemId()) {
    	 	case R.id.Quit:	// should be About
    	 		showMsg(getString(R.string.strAbout));
            	return true;
    	 	case R.id.Setup:
    	 		showDialog(SETTINGS_DLG);
    	     	return true;
    		}
    	    return false;
    	}

    	public void errExit(String errMsg){
    		if(errMsg != null) showMsg(errMsg);
    		prefs.save();
    		new AlertDialog.Builder(this).setMessage(errMsg).setCancelable(false).setPositiveButton("OK",
    				new DialogInterface.OnClickListener() { 
    					public void onClick(DialogInterface dialog, int id)	{
    			    		finish();
    			    		android.os.Process.killProcess(android.os.Process.myPid());
    					}
    				}
    			).show();
    	}

    	public void showMsg(String errMsg){
    		new AlertDialog.Builder(this).setMessage(errMsg).setCancelable(false).setPositiveButton("OK",
    				new DialogInterface.OnClickListener() { 
    						public void onClick(DialogInterface dialog, int id)	{}
    				}
    			).show();
    	}
        	
    	public void errExit(int resource) {	errExit(getString(resource)); }
    	
       	////////////////////////////////////////////////////////////////////
    	////////////////// Directory/playlist/cue handlers /////////////////
    	////////////////////////////////////////////////////////////////////

/*    	public final class Tchk implements Comparator<File>  {
    		public static final String plist_ext = ".playlist";
    		public int compare(File f1, File f2) {
        		int type1 = filetype(f1), type2 = filetype(f2);
        		if(type1 != type2) return type1 - type2;
        		return f1.getName().compareTo(f2.getName());
        	}	
    	}
    	public final Tchk TC = new Tchk(); 
*/    	
    	public static final String plist_ext = ".playlist";
    	public static final String bmark_ext = ".bmark";
    	
   	// Mp3support
    	public static final String[] audioExts = { ".flac", ".FLAC", ".ape", ".APE", ".wv", ".WV", ".mpc", ".MPC", "m4a", "M4A",
    		".wav", ".WAV", ".mp3", ".MP3", ".wma", ".WMA", ".ogg", ".OGG", ".3gpp", ".3GPP", ".aac", ".AAC" };
    	public static final String[] plistExts = { plist_ext, ".m3u", ".M3U", ".pls", ".PLS" };
    	
    	
    	public static boolean hasAudioExt(String s) {
    		for(int i = 0; i < audioExts.length; i++) {
    			if(s.endsWith(audioExts[i])) return true; 
    		}
		
    		return false;
    	}
    	public static boolean hasPlistExt(String s) {
    		for(int i = 0; i < plistExts.length; i++) {
    			if(s.endsWith(plistExts[i])) return true; 
    		}
    		return false;
    	}
    	public static boolean hasCueExt(String s) {
    		return s.endsWith(".cue") || s.endsWith(".CUE");
    	}
    	public static boolean hasAudioExt(File f) {
    		return hasAudioExt(f.toString());
    	}
    	public static boolean hasPlistExt(File f) {
    		return hasPlistExt(f.toString());
    	}
    	public static boolean hasCueExt(File f) {
    		return hasCueExt(f.toString());
    	}

    	public static int filetype(File f) {
    	  String s = f.toString();	
    		if(f.isDirectory()) return 0;
    		else if(f.toString().endsWith(bmark_ext)) return 1;
    		else if(hasCueExt(f)) return 2;
    		else if(hasPlistExt(f)) return 3;
    		else if(s.endsWith(".flac") || s.endsWith(".FLAC")) return 5;	// not using hasAudioExt to
    		else if(s.endsWith(".ape") || s.endsWith(".APE")) return 4;	// provide for different icons
    		else if(s.endsWith(".wav") || s.endsWith(".WAV")) return 7;
    		else if(s.endsWith(".wv") || s.endsWith(".WV")) return 8;
    		else if(s.endsWith(".mpc") || s.endsWith(".MPC")) return 6;
    		else if(s.endsWith(".m4a") || s.endsWith(".M4A")) return 9;
 		// Mp3support
    		else if(s.endsWith(".mp3") || s.endsWith(".MP3")) return 10;
    		else if(s.endsWith(".wma") || s.endsWith(".WMA")) return 10;
    		else if(s.endsWith(".ogg") || s.endsWith(".OGG")) return 10;
    		else if(s.endsWith(".3gpp") || s.endsWith(".3GPP")) return 10;
    		else if(s.endsWith(".aac") || s.endsWith(".AAC")) return 10;
    	
    		return 666;
    	}
    	
    	public int compare(File f1, File f2) {
    		int type1 = filetype(f1), type2 = filetype(f2);
    		if(type1 != type2) return type1 - type2;
    		return f1.getName().compareTo(f2.getName());
    	}


    	// Need to change into another directory, playlist, or cue.
    	
    	private boolean setAdapter(File fpath) {
    		if(fpath.isDirectory()) return setAdapterFromDir(fpath); 
    		else if(hasPlistExt(fpath)) return setAdapterFromPlaylist(fpath);
    		else if(hasCueExt(fpath)) return setAdapterFromCue(fpath);
    		return false;
    	}

    	// This function is called after long-press of a directory, playlist, or cue.
    	// Playback starts immediately without changing the current directory.
    	
    	private boolean playPath(File fpath) {
    		if(fpath.isDirectory()) return playDir(fpath); 
    		else if(hasPlistExt(fpath)) return playPlaylist(fpath);
    		else if(hasCueExt(fpath)) return playCue(fpath);
    		return false;
    	}
        	
    	/////////////////////////////////////////////////
    	////////////// Ordinary directories /////////////

    	private class parsed_dir {
    		File [] filez;
    		int dirs, cues, flacs;
    	}
    	
    	private parsed_dir parseDir(File fpath) {
    		
    		int dirs = 0, cues = 0, flacs = 0;
			
    		File [] filez = fpath.listFiles();
			
			if(filez.length == 0) return null;
		    		
			Comparator<File>cmp = this;
			Arrays.sort(filez,	cmp);  
			    			
			// now sorted in order of types (dirs/flacs/other), and alphabetically within each type
		
			for(int i = 0; i < filez.length; i++) {
				switch(filetype(filez[i])) {
					case 0:	
						dirs++; break;
					case 1:	case 2:
					case 3:		
						cues++; break;
					case 4: case 5: 
					case 6: case 7: 
					case 8:	case 9:	
					case 10:	
						flacs++; break;
					default: break;
				}
			}
			if(dirs + cues + flacs == 0) return null;  
			parsed_dir r = new parsed_dir();
			r.dirs = dirs; r.cues = cues; r.flacs = flacs;
			r.filez = new File[dirs+cues+flacs];
			for(int i = 0; i < dirs + cues + flacs; i++) r.filez[i] = filez[i];
			return r;
    	}
   	
    	private boolean playDir(File fpath) {
    		parsed_dir r = parseDir(fpath);
    		
    		if(r == null) {
				Toast.makeText(getApplicationContext(), R.string.strNoFiles, Toast.LENGTH_SHORT).show();
				return false;
			}
			
    		ArrayList<String> filly = new ArrayList<String>();
			for(int i = r.dirs + r.cues; i < r.filez.length; i++) filly.add(r.filez[i].toString());
			
			return playContents(fpath.toString(),filly,null,null,0,0);
    	}

    	private ArrayList<String> recurseDir(File fpath) {
    		if(!fpath.isDirectory()) return null;
    		ArrayList<String> filez = new ArrayList<String>();
   			parsed_dir d = parseDir(fpath);
   				if(d == null) return null;
   				for(int i = d.dirs + d.cues; i < d.filez.length; i++) filez.add(d.filez[i].toString());
   				for(int i = 0; i < d.dirs; i++) {
   					ArrayList<String> r = recurseDir(d.filez[i]);
   					if(r != null) for(int k = 0; k < r.size(); k++) filez.add(r.get(k));
   				}
    		return filez;
    	}
    	
    	private boolean setAdapterFromDir(File fpath) {
    		try {

        		File [] filez;
        		int dirs, cues, flacs;

        		parsed_dir r = parseDir(fpath);
    			
        		if(r == null) {
    				Toast.makeText(getApplicationContext(), R.string.strNoFiles, Toast.LENGTH_SHORT).show();
    				return false;
    			}
    			
        		filez = r.filez;	dirs = r.dirs;
    			cues = r.cues;  	flacs = r.flacs;
        		
    			cur_path = fpath;
    			first_file_pos = dirs + cues;
    			
    			files.clear();
    			if(track_names.size() > 0) track_names.clear();
    			if(start_times.size() > 0) start_times.clear();
    			directoryEntries.clear();
    					
    			Drawable dir_icon = getResources().getDrawable(R.drawable.folder);
    			Drawable aud_icon = getResources().getDrawable(R.drawable.audio1);
    			Drawable cue_icon = getResources().getDrawable(R.drawable.plist);
    			
    			int plen = cur_path.toString().length();
    			if(cur_path.toString().compareTo("/") != 0) plen++;
        			
    			for(int i = 0; i < dirs + cues + flacs; i++) {
    				String s  = filez[i].toString().substring(plen);
    				if(i < dirs) directoryEntries.add(new IconifiedText(s,dir_icon));
    				else if(i < dirs + cues) directoryEntries.add(new IconifiedText(s,cue_icon));
    				else directoryEntries.add(new IconifiedText(s,aud_icon));
    				files.add(filez[i].toString());			
    			}	

				buttUp.setEnabled(cur_path.getParentFile()!= null);
    			IconifiedTextListAdapter ita = new IconifiedTextListAdapter(this);
    			ita.setListItems(directoryEntries);
    			ita.setFontSize(prefs.fsize);
    			fileList.setAdapter(ita);
    			return true;
    			
    	    } catch (Exception e) {
    	    	log_err("Exception in setAdapterFromDir(): " + e.toString());
    	    	return false;
    	    }
  			
    	}

    	/////////////////////////////////////////////////
    	////////////// Playlist files ///////////////////

    	private ArrayList<String> parsePlaylist(File fpath) {
	    	
    		ArrayList<String> filez = new ArrayList<String>();
    		
    		try {
	    
    			BufferedReader reader = new BufferedReader(new FileReader(fpath), 8192);
    			String line = null;
    			String path = cur_path.toString();
    			if(!path.endsWith("/")) path += "/";
    			
    			while ((line = reader.readLine()) != null) {
	    			line = line.trim();
	    			if(line.startsWith("File") && line.indexOf('=') > 4) {	// maybe it's a PLS file
	    				int k, idx = line.indexOf('=');
	    				for(k = 4; k < idx; k++) if(line.charAt(k) < '0' || line.charAt(k) > '9') break;
	    				if(k != idx || idx == line.length()) continue;
	    				line = line.substring(idx+1);
	    			}
    				File f = new File(line);
	    			if(f.exists() && !f.isDirectory() && hasAudioExt(line)) {
	    				filez.add(line);
	    				continue;
	    			}
	    			f = new File(path+line);	// maybe it was a relative path 
	    			if(f.exists() && !f.isDirectory() && hasAudioExt(path+line)) filez.add(path+line);
    			}
	        
	    		if(filez.size() == 0) {
	    			Toast.makeText(getApplicationContext(), R.string.strBadPlist, Toast.LENGTH_SHORT).show();
	    			return null;
	    		}

    	        if(prefs.shuffle) {
    	        	Random rr = new Random();
    	        	ArrayList<String> filly = new ArrayList<String>();
    	        	for(int i = filez.size(); i > 0; i--) {	
    	        		int k = rr.nextInt(i);
    	        		filly.add(filez.get(k));
    	        		filez.remove(k);
    	        	}
    	        	return filly;
    	        }
	    		return filez;
	    	} catch (Exception e) {
    	    	log_err("Exception in parsePlaylist(): " + e.toString());
    	    	filez = null;
    	    	return null;
        	}
    	}
    	
    	private boolean playPlaylist(File fpath) {
	        ArrayList<String> filez = parsePlaylist(fpath);
	        if(filez == null) return false;
    		return playContents(fpath.toString(),filez,null,null,0,0);
    	}

    	private boolean setAdapterFromPlaylist(File fpath) {
    	    try {

    	        ArrayList<String> filez = parsePlaylist(fpath);
    	        if(filez == null) return false;
    	
    	        cur_path = fpath;
    	        first_file_pos = 0;    	        

    	        files.clear();
    	        if(track_names.size() > 0) track_names.clear();
    	        if(start_times.size() > 0) start_times.clear();
    	        directoryEntries.clear();

    	        Drawable aud_icon = getResources().getDrawable(R.drawable.audio1);

        	    for(int i = 0; i < filez.size(); i++) {
        			String s = filez.get(i);
        			files.add(s);
        			int k = s.lastIndexOf('/');
        	       	if(k >= 0) s = s.substring(k+1);
        	       	directoryEntries.add(new IconifiedText(s,aud_icon));
        	       	
        	    }	
    	        
        	    buttUp.setEnabled(true);
    	        IconifiedTextListAdapter ita = new IconifiedTextListAdapter(this);
    	        ita.setListItems(directoryEntries);
    	        ita.setFontSize(prefs.fsize);
    	        fileList.setAdapter(ita);
    	    		
    	        return true;
    	    
    	    } catch (Exception e) {
    	    	log_err("exception in setAdapterFromPlaylist(): " + e.toString());
    	        return false;
    	    }
    	}
    	
        ////////////////////////////////////////
    	////////////// CUE files /////////////// 

    	private class parsed_cue {
    		ArrayList<String> filez;
    		ArrayList<String> namez;
    		ArrayList<Integer> timez;
    	}
    	
    	private parsed_cue parseCue(File fpath) {
    		try {
    	       
    			BufferedReader reader = new BufferedReader(new FileReader(fpath), 8192);
    	        
    	        String line = null;
    	        ArrayList<String> filez = new ArrayList<String>();
    	        ArrayList<String> namez = new ArrayList<String>();
    	        ArrayList<Integer> timez = new ArrayList<Integer>();
    	        
    	        String cur_file = null;
    	        String cur_track_title = null; 
    	        int cur_track = 0, minutes = 0, seconds = 0;
    	        
    	        String path = fpath.toString();
    	        int plen = path.lastIndexOf('/');
    	        path = (plen < 0) ? "/" : path.substring(0, plen+1);

    	        // Simple CUE parser
    	        
    	        while ((line = reader.readLine()) != null) {
    	        	String s = line.trim();
        		// 	log_msg("first trying: " + s);
    	        	if(s.startsWith("FILE ") /* && s.endsWith(" WAVE") */) {
    	        		if(s.charAt(5) == '\"') {
    	        			int i = s.lastIndexOf('\"');
    	        			if(i < 7) continue;
    	        			cur_file = s.substring(6, i);
    	        		} else {
    	        			int i = s.lastIndexOf(' ');
    	        			if(i < 6) continue;
    	        			cur_file = s.substring(5, i);
    	        		}
    	        		File ff = new File(path + cur_file);
    	       	//		log_msg("trying: " + ff.toString());
    	        		if(!ff.exists()) {	
    	        			// sometimes cues reference source with wrong extension
    	        			int kk = (path + cur_file).lastIndexOf('.');
    	        			if(kk > 0) {	
    	        				File f = null;
    	        				String ss = (path + cur_file).substring(0, kk);
    	        				for(kk = 0; kk < audioExts.length; kk++) {
    	        					f = new File(ss + audioExts[kk]);
    	        					if(f.exists()) break; 
    	        				}
    	        				if(kk < audioExts.length) {
    	        					cur_file = cur_file.substring(0,cur_file.lastIndexOf('.')) + audioExts[kk];
    	        					log_msg("WARNING! using cue source with different extension: " + cur_file);
    	        					continue;
    	        				}
    	        			}	
    	        			Toast.makeText(getApplicationContext(), R.string.strNoCueSrc, Toast.LENGTH_SHORT).show();
    	        			return null;
    	        		} else if(!hasAudioExt(ff)) {
    	        			Toast.makeText(getApplicationContext(), R.string.strCueSrcExt, Toast.LENGTH_SHORT).show();
    	        			return null;
    	        		}
    	        		
    	        	} else if(s.startsWith("TRACK ") && s.endsWith(" AUDIO")) {
    	        		String tr = s.substring(6, 8);
    	        		cur_track = (Integer.valueOf(tr)).intValue();
    	           	} else if(s.startsWith("TITLE ")) {
    	        		if(s.charAt(6) == '\"') { 
    	        			int i = s.lastIndexOf('\"');
    	        			if(i < 8) continue;
    	        			cur_track_title = s.substring(7, i);
    	        		}	 else {
    	        			int i = s.length();
    	        			if(i < 7) continue;
    	        			cur_track_title = s.substring(6);
    	        		}
    	        	} else if(s.startsWith("INDEX 01 ")) {
    	        		if(s.charAt(11) != ':' || s.charAt(14) != ':') {
    	        			log_msg("CUE: invalid timecode format for track " + cur_track);
    	        			continue;
    	        		}
    	        		String tr = s.substring(9, 11);
    	        		minutes = (Integer.valueOf(tr)).intValue();
    	        		tr = s.substring(12, 14);
    	        		seconds = (Integer.valueOf(tr)).intValue();
    	        		if(cur_file == null) continue;
    	        		filez.add(path + cur_file);
    	        		if(cur_track_title == null) namez.add("track " + cur_track);
    	        		else namez.add(cur_track_title);
    	        		timez.add(minutes * 60 + seconds);
    	        //		log_msg("added: " + cur_track_title + " at " + minutes + " min.");
    	        		cur_track_title = null;
    	        	}
    	        }
    	        if(filez.size() < 1) {
    	        	Toast.makeText(getApplicationContext(), R.string.strBadCue, Toast.LENGTH_SHORT).show();
    	        	return null;
    	        }
    	        parsed_cue r = new parsed_cue();
    	        r.filez = filez;
    	        r.namez = namez;
    	        r.timez = timez;
    	        return r;
    	        
    		} catch (Exception e) {
    	    	log_err("exception in parseCuet(): " + e.toString());
    		}
    		Toast.makeText(getApplicationContext(), R.string.strBadCue, Toast.LENGTH_SHORT).show();
    		return null;
    	}
     	
    	private boolean playCue(File fpath) {
    		parsed_cue r = parseCue(fpath);
    		if(r == null) return false;
    	    return playContents(fpath.toString(),r.filez,r.namez,r.timez,0,0);
    	}
    	
    	private boolean setAdapterFromCue(File fpath) {
    		try {
    	        ArrayList<String> filez;
    	        ArrayList<String> namez;
    	        ArrayList<Integer> timez;

    	        parsed_cue r = parseCue(fpath);
        		if(r == null) return false;
        		
        		filez = r.filez;
        		namez = r.namez;
        		timez = r.timez;
    	            	        
    	        cur_path = fpath;
    	        first_file_pos = 0;
    	        
    	        files.clear();
    	        if(track_names.size() > 0) track_names.clear();
    	        if(start_times.size() > 0) start_times.clear();
    	        directoryEntries.clear();
    	        	
    	        Drawable aud_icon = getResources().getDrawable(R.drawable.audio1);
    	        	
    	        for(int i = 0; i < filez.size(); i++) {
    	        	files.add(filez.get(i));
    	        	track_names.add(namez.get(i));
    	        	start_times.add(timez.get(i));
    	        	directoryEntries.add(new IconifiedText(namez.get(i),aud_icon));
    	        }	
        			
    			buttUp.setEnabled(true);
    			IconifiedTextListAdapter ita = new IconifiedTextListAdapter(this);
    			ita.setListItems(directoryEntries);
    			ita.setFontSize(prefs.fsize);
    			fileList.setAdapter(ita);
    	
    	        return true;
    		
    		} catch (Exception e) {
    	    	log_err("exception in setAdapterFromCue(): " + e.toString());
    	    	return false;
    		}
    	}

}

