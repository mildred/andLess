package net.avs234;	

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
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
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.SeekBar.OnSeekBarChangeListener;

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

    	// At the start, set this flag and emulate the pause if the last file was bookmarked 
    	private boolean pause_on_start = false;
    	
    	// Changed to true in playlists/settings dialogs
    	private boolean playlist_changed = false;
    	
    	private void log_msg(String msg) {
    		Log.i(getClass().getSimpleName(), msg);
    	}
    	private void log_err(String msg) {
    		Log.e(getClass().getSimpleName(), msg);
    	}
  		
    	// UI elements defined in layout xml file.
		private Button buttPause, buttPrev, buttNext, buttVMinus, buttVPlus, ButtonVolume;
		private TextView nowTime, allTime;
    	private ListView fileList;
    	private SeekBar pBar;
    	private String curWindowTitle = null; 	
    	private static final String resume_bmark = "/resume.bmark";
        // Interface which is an entry point to server functions. Returned upon connection to the server. 
        private IAndLessSrv srv = null;
        // If we're called through intent
        private String startfile = null;
        
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
    		public void playItemPaused(boolean paused) {
    			pauseResumeHandler.sendEmptyMessage(paused ? 1 : 0);
    		}
    	};
    	
    	IBinder.DeathRecipient bdeath = new IBinder.DeathRecipient() {
    		public void binderDied() {
				log_err("Binder died, trying to reconnect");
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
    				if(startfile != null) {	// we've been called via intent.VIEW
    					log_msg("connection using "+startfile);
    					File f = new File(startfile);
    					if(f.exists() && !f.isDirectory() && hasAudioExt(startfile)) {
    						int i = startfile.lastIndexOf('/');
							f = new File(startfile.substring(0, i));
    						if(setAdapter(f) && i > 0) {
    							log_msg("starting from \"" + startfile + "\" in \""  + f.toString() + "\"");
    							srv.registerCallback(cBack);
    		    				update_headset_mode(null);
    		    				playDir(f,startfile);
    							return;
    						}	
    					} else if(f.exists() && (f.isDirectory() || hasPlistExt(startfile) || hasCueExt(startfile))) {
    						if(setAdapter(f)) {
    		    				srv.registerCallback(cBack);
    		    				update_headset_mode(null);
    							playPath(f);
    							return;
    						}
    					}
    				}
        			String s = srv.get_cur_dir();
    				if(s != null) {
    					File f = new File(s);
    					if(f.exists() && (f.isDirectory() || hasPlistExt(s) || hasCueExt(s))) {
    						if(setAdapter(f)) { 
    							log_msg("restored previous playlist");
    							int i = srv.get_cur_pos() + 1;
    							if(i >= 0 && first_file_pos + i < directoryEntries.size()) {
    								fileList.setSelection(first_file_pos+i);
    							}
    							if(srv.is_paused()) { 
    								cBack.playItemChanged(true,getString(R.string.strPaused));
    								buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_play));
    							} else if(srv.is_running()) {
    								cBack.playItemChanged(false,directoryEntries.get(first_file_pos+i).getText());
    								buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
    							} else cBack.playItemChanged(true,getString(R.string.strStopped));
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
    		            if(prefs.last_played_file != null && (new File(prefs.last_played_file)).exists() && !srv.is_running()) {
        					log_msg("bookmarked, starting from paused state");
    		            	cBack.playItemChanged(true,getString(R.string.strPaused));
        					pause_on_start = true;
        					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_play));
    					} else cBack.playItemChanged(true,getString(R.string.strStopped));
    				}
    				srv.registerCallback(cBack);
    				update_headset_mode(null);
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
	    					if(pause_on_start) return change_to_pause_btn;
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
    					if(pause_on_start) selItem.onItemClick(null, null, 0, 0); // dirty hack
    					if(now_playing != null) getWindow().setTitle(now_playing);
    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
    					break;
    				case change_to_play_btn:    					
    					getWindow().setTitle(getString(R.string.strPaused));
    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_play));
    					break;
    			}
    			pause_on_start = false;
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
    								if(now_playing != null) {
    									int i = now_playing.lastIndexOf('/'); 
    									if(i >= 0) now_playing = now_playing.substring(i+1);
    								}
    							}
    						  }
  							}  
  	    					if(now_playing != null) getWindow().setTitle(now_playing);
  	    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
  						} else if(srv.pause()) {
  	    					getWindow().setTitle(getString(R.string.strPaused));
  	    					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_play));
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
        View.OnClickListener onButtonVolume = new OnClickListener() {
        	public void onClick(View v) { 
        		showDialog(VOLUME_DLG); 
        	}	
        };
    	
    	private void ExitFromProgram() {
    		try {
				if(srv != null) {
					if(srv.is_running()) saveBook();
					srv.shutdown();	
				}
			} catch (Exception e) {
				log_err("exception while shutting down");
			}
			prefs.save();
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
    	
    	OnSeekBarChangeListener onSeekBar = new OnSeekBarChangeListener() {

			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				String sTime = (progress < 3600) ? String.format("%d:%02d", progress/60, progress % 60) 
						:	String.format("%d:%02d:%02d", progress/3600, (progress % 3600)/60, progress % 60);
   				nowTime.setText(sTime);
			}

			public void onStartTrackingTouch(SeekBar seekBar) {
				
			}

			public void onStopTrackingTouch(SeekBar seekBar) {
				try {
					if(srv.get_cur_mode() == 0) {
						srv.seek_to(seekBar.getProgress()*1000);
					} else {
						srv.play(srv.get_cur_pos(), seekBar.getProgress());
					}
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		};
    	
    	private void onButtUp() {
    		if(cur_path == null) return;
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
    			if(!pause_on_start) buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
    			return true;
			} catch (Exception e) { 
				log_err("exception in playContents: " + e.toString()); 
				e.printStackTrace();
				return false;
			}
    		
    	}
    	// Save the last played file in format "file:pos:time"
    	// Also saves this info to preferences.
    	void saveBook() {
    		try {
        		int i;
    			File book_file;
        		String s =  srv.get_cur_dir();

        		if (hasPlistExt(s) || hasCueExt(s)) {
        			i = s.lastIndexOf('/');
    				book_file = new File(s.substring(0, i) + resume_bmark);
        		} else {
    				book_file = new File(s + resume_bmark);
    				s = srv.get_cur_track_source();
    			}
				prefs.last_played_file = new String(s);
    			int seconds = srv.get_cur_seconds() - srv.get_cur_track_start();
    			int index = srv.get_cur_pos();
    			prefs.last_played_pos = index;
    			prefs.last_played_time = seconds;
        		if(!prefs.savebooks) return;
        		if(book_file.exists()) book_file.delete();
    			BufferedWriter writer = new BufferedWriter(new FileWriter(book_file, false), 8192);
    			String g = s + String.format(":%d:%d", seconds, index);   
    			writer.write(g);
   			   	writer.close();
   			   	log_msg("Saving bookmark: " + book_file.toString() + ": " + g);
    		} catch (Exception e) { 
				log_err("exception in saveBook: " + e.toString()); 
			}
    	}
    	
    	////////////  Change to the selected directory/cue/playlist, or play starting from the selected track 
    	AdapterView.OnItemClickListener selItem = new OnItemClickListener() {

    		public void onItemClick(AdapterView<?> a, View v, int i,long k) {

    			pause_on_start = false;
    			if(i==0 && a != null) {
    				onButtUp();
    				return;
    			}
    			k = k-1;
				if((int) k >= files.size() && a != null) {
					log_err("cilcked item out of range! i: "+i+" k: "+k);
					return;
				}
				
				File f = (a != null) ? f = new File(files.get((int)k)) : null;
				
				//if(f != null && f.exists()) {
					if(f != null && f.toString().endsWith(bmark_ext)) {
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
    						if(!srv.play(track-first_file_pos,seconds)) {
    							Toast.makeText(getApplicationContext(), R.string.strSrvFail, Toast.LENGTH_SHORT).show();
    							log_err("failed to start playing <bookmarked file>"); 
    						}
    						buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
    						return;
			    		} catch (Exception e) { 
							log_err("exception while processing bookmark file!");
						}	
	    				Toast.makeText(getApplicationContext(), R.string.strBadBook, Toast.LENGTH_SHORT).show();
	    				return;
					}
					if(k < first_file_pos && f != null) {	// Directory, cue or playlist was clicked in the list 
    					if(!setAdapter(f)) {
    						log_err("error setting adapter for " + f.toString());
    					}
    				} else try {				// Regular file, or cue track. first_file_pos is always 0 for playlists and cues.
						if(srv.is_running() && f != null) saveBook();
    					String cc;
    					String cdr;
    					String fileToPlay = null;
    					int curpos;
    					int curtime;
    					if(f == null) {
    						if(prefs.last_played_file == null) return;
    						File ff = new File(prefs.last_played_file);
    						if(!ff.exists()) return;
    						if(hasPlistExt(ff) || hasCueExt(ff)) {
	    	        			if(!setAdapter(ff)) {
	    	        					log_err("error setting adapter for " + ff.toString());
	    	        					return;
	    	        			}
	    	        			cdr = cur_path.toString();
    						} else {
    							curpos = prefs.last_played_file.lastIndexOf('/');
    							if(curpos < 0) return;
    							cdr =  prefs.last_played_file.substring(0, curpos);
    		        			if(!setAdapter(new File(cdr))) {
    	        					log_err("error setting adapter for " + ff.toString());
    	        					return;
    		        			}
    						}
    						fileToPlay =  null;
    						curpos = prefs.last_played_pos;
    						curtime = prefs.last_played_time;
    						
    						cc = null;
    						log_msg(String.format("Resuming from file %s in %s, idx=%d time=%d",prefs.last_played_file, cdr,curpos,curtime));
    					} else {
    						curpos = (int)k - first_file_pos;
    						curtime = 0;
    						cc = srv.get_cur_dir();
    						cdr = cur_path.toString();
    						fileToPlay = files.get((int)k);
    						log_msg(String.format("Attempting to play file %d in %s",(int)k - first_file_pos,cdr));
    					}
    					    					    					
    					if(fileToPlay != null && (fileToPlay.endsWith(".flac") || fileToPlay.endsWith(".FLAC"))) {
    						File q = new File(fileToPlay);
    						String s = fileToPlay.substring(0, fileToPlay.lastIndexOf('.'))+ ".cue";
    						File qz = new File(s);
    						if(q.exists() && !q.isDirectory() && !qz.exists()) {
    							int [] qq = AndLessSrv.extractFlacCUE(fileToPlay);  //srv.get_cue_from_flac(fileToPlay);
    							if(qq != null) {
    								log_msg("Saving embedded CUE from " + fileToPlay + " to " + s);
    								BufferedWriter writer = new BufferedWriter(new FileWriter(s, false), 8192);
    				    			writer.write("FILE \"" + fileToPlay.substring(fileToPlay.lastIndexOf('/')+1) + "\" WAV\n");
    								for(int j = 0; j < qq.length; j++) {
    									int track_time = qq[j];	
    									String tr = String.format("  TRACK %02d AUDIO\n    TITLE \"%s %d\"\n    INDEX 01 ", 
    											j+1, getString(R.string.strCueTrack),j+1);    											
    									String gg = String.format((track_time < 3600) ? "%02d:%02d:00\n" : "%d:%02d:00\n", track_time/60, track_time % 60);
    									writer.write(tr + gg);
    								}
    				   			   	writer.close();
    		    					if(!setAdapter(qz)) {
    		    						log_err("error setting adapter for new cue " + qz.toString());
    		    					}
    				   			   	return;
    							} else log_msg("No embedded CUE found");
    						}
    					}
    					if(cc == null || cdr.compareTo(cc) != 0 || playlist_changed) {
    						playlist_changed = false;
    						if(cur_path.isDirectory()) {
    							ArrayList<String> filly = new ArrayList<String>();
    							for(int j = first_file_pos; j < files.size(); j++) filly.add(files.get(j));
    							playContents(cdr,filly,null,null,curpos,curtime);
    						} else if(hasCueExt(cdr)) {
    							playContents(cdr,files,track_names,start_times,curpos,curtime);
    						} else if(hasPlistExt(cdr)) {
    							playContents(cdr,files,null,null,curpos,curtime);
    						}
    					} else {
    						srv.set_driver_mode(prefs.driver_mode);
    						if(!srv.play(curpos,curtime)){
    							Toast.makeText(getApplicationContext(), R.string.strSrvFail, Toast.LENGTH_SHORT).show();
    							log_err("failed to start playing <single file>"); 
    						}
    						buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
    					}
    				} catch (Exception e) { 
    					e.printStackTrace();
    					log_err("exception in selItem: " + e.toString()); 
    				}
    			//} else log_err("Attempt to play non-existing file");
    		}
    	};

    	// If a playlist or cue was long-pressed, its contents are sent to server, and playback  
    	// starts immediately without changing into that playlist. 

    	private int cur_longpressed = 0;
    	AdapterView.OnItemLongClickListener pressItem = new OnItemLongClickListener() {
			public boolean onItemLongClick(AdapterView<?> a, View v, int i, long k) {
				if(i==0) {
					onButtUp();
					return false;
				}
    			k = k-1;
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
					track_longpressed = i; // set which position clicked
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
			private final int update_period = 500;
			
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
								if(!pBar.isPressed()) { 
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
						       				String sTime = (track_time < 3600) ? String.format("%d:%02d", track_time/60, track_time % 60) 
													:	String.format("%d:%02d:%02d", track_time/3600, (track_time % 3600)/60, track_time % 60);
											allTime.setText(sTime);
						   					need_update = false;
						   				}
									//	pBar.setProgress(srv.get_cur_seconds() - AndLessSrv.curTrackStart);
						   				int progress = srv.get_cur_seconds() - srv.get_cur_track_start();
						   				if(progress > 0) pBar.setProgress(progress);
						   				String sTime = (srv.get_cur_seconds() < 3600) ? String.format("%d:%02d", progress/60, progress % 60) 
												:	String.format("%d:%02d:%02d", progress/3600, (progress % 3600)/60, progress % 60);
						   				nowTime.setText(sTime);
									} catch (Exception e) { 
										log_err("exception 1 in progress update handler: " + e.toString()); 
									}
								}
							}
						});
						return;
					}
					progressUpdate.post(new Runnable() {	// initialize
						public void run() {
							if(srv == null) return;
							if(!pBar.isPressed()) {
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
									String sTime = (track_time < 3600) ? String.format("%d:%02d", track_time/60, track_time % 60) 
											:	String.format("%d:%02d:%02d", track_time/3600, (track_time % 3600)/60, track_time % 60);
									allTime.setText(sTime);
								} catch (Exception e) { 
									log_err("exception 2 in progress update handler: " + e.toString()); 
								}
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
              		  // if(pBar != null) pBar.setProgress(0);
              		  if(!samsung) ttu.start(curfile);
              		  else getWindow().setTitle(curfile);
              		  if(buttPause != null) buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
              		  return;
              	  } else {
              	//	  if(pBar != null) pBar.setProgress(0);
              		  getWindow().setTitle(curfile);
              		  if(!samsung) ttu.shutdown();
              		  if(buttPause != null) buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_play));
              		  return;
              	  }
                }
                if(!samsung) ttu.shutdown();
        		// if(pBar != null) pBar.setProgress(0);
                curfile = bb.getString("errormsg");
                if(curfile == null) return;
                showMsg(curfile);
      	  }
      };
    	     
      Handler pauseResumeHandler = new Handler() {
		private String now_playing = null;
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch(msg.what) {
				case 0:
					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_pause));
					if(now_playing != null) getWindow().setTitle(now_playing);
					break;
				case 1:	
					now_playing = curWindowTitle;
					buttPause.setBackgroundDrawable(getResources().getDrawable(R.drawable.s_play));
					getWindow().setTitle(getString(R.string.strPaused));
					break;
			}
		}
      };
      
    	////////////////////////////////////////////////////////////////
    	///////////////////////// Entry point //////////////////////////
    	////////////////////////////////////////////////////////////////
      
        protected void onResume() {
        	super.onResume();
        	
        	// getting settings
        	SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        	boolean s_m = settings.getBoolean("shuffle_mode", false);
        	boolean d_m = settings.getBoolean("driver_mode", false);
        	boolean b_m = settings.getBoolean("book_mode", false);
        	if(d_m) {
        		prefs.driver_mode = AndLessSrv.MODE_DIRECT;
        		ButtonVolume.setVisibility(View.VISIBLE);
        	} else {
        		prefs.driver_mode = AndLessSrv.MODE_CALLBACK;
        		ButtonVolume.setVisibility(View.GONE);
        	}
			if(b_m) {
				prefs.savebooks = true;
			} else {
				prefs.savebooks = false;
			}
			if(s_m != prefs.shuffle) {
				playlist_changed = true;
			}
			if(s_m) {
				prefs.shuffle = true;
			} else {
				prefs.shuffle = false;
			}
            update_headset_mode(settings);
        }

        void update_headset_mode(SharedPreferences settings) {
        	if(settings == null) settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
			prefs.headset_mode = 0;
            if(settings.getBoolean("hs_remove_mode", false)) prefs.headset_mode |= AndLessSrv.HANDLE_HEADSET_REMOVE;
            if(settings.getBoolean("hs_insert_mode", false)) prefs.headset_mode |= AndLessSrv.HANDLE_HEADSET_INSERT;
            if(srv != null) try {
            	srv.set_headset_mode(prefs.headset_mode);
            } catch (RemoteException r) {
            	log_err("remote exception while trying to set headset_mode");
            }
        }
        
    	@Override
        public void onCreate(Bundle savedInstanceState) {

    		super.onCreate(savedInstanceState);

            Intent ii = getIntent();
    		prefs = new Prefs();
            prefs.load();

            // ui preferences
            setTheme(android.R.style.Theme_Light);
            setContentView(R.layout.main);
            setContent();
            
			fileList.setBackgroundResource(android.R.color.background_light); 
            
            buttPause.setEnabled(true);

            Intent intie = new Intent();
            intie.setClassName("net.avs234", "net.avs234.AndLessSrv");
            
            if(startService(intie)== null) log_msg("service not started");
            else log_msg("started service");

            if(conn == null) conn = new_connection();

            if(ii.getAction().equals(Intent.ACTION_VIEW) || ii.getAction().equals(AndLessSrv.ACTION_VIEW)) { 
            	try {
            		startfile = Uri.decode(ii.getDataString());
            		if(startfile != null && startfile.startsWith("file:///")) startfile = startfile.substring(7);
            		else startfile = null;
            	} catch (Exception s) {  startfile = null; } 
            } else startfile = null;
            
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
        	public String last_path;
        	public String plist_path;
        	public String plist_name;
        	public String last_played_file;
        	public boolean shuffle;
        	public boolean savebooks;
        	public int driver_mode;
        	public int headset_mode;
        	public int last_played_pos;
        	public int last_played_time;
        	public void load() {
        		SharedPreferences shpr = getSharedPreferences(PREFS_NAME, 0);
                shuffle = shpr.getBoolean("shuffle", false);		
                savebooks = shpr.getBoolean("save_books", false);
                driver_mode = shpr.getInt("driver_mode", AndLessSrv.MODE_CALLBACK);
                last_path = shpr.getString("last_path", null);
                last_played_file = shpr.getString("last_played_file", null);
                last_played_pos = shpr.getInt("last_played_pos",0);
                last_played_time = shpr.getInt("last_played_time",0);
                plist_path = shpr.getString("plist_path", Environment.getExternalStorageDirectory().toString());
                plist_name = shpr.getString("plist_name", "Favorites");
                headset_mode = 0;
                if(shpr.getBoolean("hs_remove_mode", false)) headset_mode |= AndLessSrv.HANDLE_HEADSET_REMOVE;
                if(shpr.getBoolean("hs_insert_mode", false)) headset_mode |= AndLessSrv.HANDLE_HEADSET_INSERT;
        	}
        
        	public void save() {
        	  	SharedPreferences shpr = getSharedPreferences(PREFS_NAME, 0);
        	  	SharedPreferences.Editor editor = shpr.edit();
        	  	editor.putBoolean("shuffle", shuffle);
        	  	editor.putBoolean("save_books", savebooks);
        	  	editor.putInt("driver_mode", driver_mode);
        	  	editor.putBoolean("hs_remove_mode", (headset_mode & AndLessSrv.HANDLE_HEADSET_REMOVE) != 0);
        	  	editor.putBoolean("hs_insert_mode", (headset_mode & AndLessSrv.HANDLE_HEADSET_INSERT) != 0);
        	  	if(cur_path != null) editor.putString("last_path", cur_path.toString());
        	  	if(plist_path != null) editor.putString("plist_path", plist_path);
        	  	if(plist_name != null) editor.putString("plist_name", plist_name);
        	  	if(last_played_file != null) {
        	  		editor.putString("last_played_file", last_played_file);
        	  		editor.putInt("last_played_pos", last_played_pos);
        	  		editor.putInt("last_played_time", last_played_time);
        	  	}
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
            buttPause = (Button) findViewById(R.id.ButtonPause);
            buttPrev = (Button) findViewById(R.id.ButtonPrev);
            buttNext = (Button) findViewById(R.id.ButtonNext);
            
            ButtonVolume = (Button) findViewById(R.id.ButtonVolume);
            fileList = (ListView) findViewById(R.id.FileList);
            nowTime = (TextView) findViewById(R.id.nowTime);
            allTime = (TextView) findViewById(R.id.allTime);
            pBar = (SeekBar) findViewById(R.id.PBar);
            pBar.setOnSeekBarChangeListener(onSeekBar);
            buttPause.setOnClickListener(onButtPause);
            buttPrev.setOnClickListener(onButtPrev);
            buttNext.setOnClickListener(onButtNext);
            fileList.setOnItemClickListener(selItem);
            fileList.setOnItemLongClickListener(pressItem);
            ButtonVolume.setOnClickListener(onButtonVolume);
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
      	static final int VOLUME_DLG = 3;
      	
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
    			/*case EDIT_PLAYLIST_DLG:
    			AlertDialog submenu = (AlertDialog) dialog;
    			submenu.setTitle(directoryEntries.get(track_longpressed).getText().toString());
    			break;*/
    		   	case ADD_PLAYLIST_DLG:
    		   		AlertDialog aDialog = (AlertDialog)dialog;
    		   		aDialog.setTitle(getString(R.string.strAddPlist));
       				((EditText) aDialog.findViewById(R.id.EditPlaylistPath)).setText(prefs.plist_path);
       				((EditText) aDialog.findViewById(R.id.EditPlaylistName)).setText(prefs.plist_name);
       				TextView chbt = (TextView) aDialog.findViewById(R.id.CheckBoxText);
       				chbt.setEnabled(false);
       				chb = (CheckBox) aDialog.findViewById(R.id.CheckRecursive);
       				chb.setChecked(false);
       				
       				File f = new File(files.get(cur_longpressed));
       				if(!f.isDirectory()) {
       					chb.setEnabled(false);
       					chbt.setEnabled(false);
       				} else {
       					chb.setEnabled(true);
       					chbt.setEnabled(true);
       				}
       				break;
    		   	case VOLUME_DLG:
    		   		buttVMinus = (Button) dialog.findViewById(R.id.ButtonVMinus);
       	            buttVPlus = (Button) dialog.findViewById(R.id.ButtonVPlus);
       	            buttVMinus.setOnClickListener(onButtVMinus);
       	            buttVPlus.setOnClickListener(onButtVPlus);
       			break;
    		   	default:
    				break;
    		}
    	}
    	
    	protected Dialog onCreateDialog(int id) {
			Dialog dialog;
			LayoutInflater factory = LayoutInflater.from(this);
			switch(id) {
			
   			 case ADD_PLAYLIST_DLG:
   				final View newView = factory.inflate(R.layout.add_plist, null); 
   				AlertDialog.Builder add_playlist_dlg = new AlertDialog.Builder(this);
   				add_playlist_dlg.setView(newView);
   				add_playlist_dlg.setTitle(R.string.strAddPlist);
   				add_playlist_dlg.setPositiveButton(R.string.strAdd, new DialogInterface.OnClickListener() {
   			        public void onClick(DialogInterface dialog, int whichButton) {
   			        	Log.i("andless", "-1");
   			      // save and reload playlist
   		    			String path = ((EditText) newView.findViewById(R.id.EditPlaylistPath)).getText().toString();
   		    			String name = ((EditText) newView.findViewById(R.id.EditPlaylistName)).getText().toString();
   		    			Log.i("andless", "0");       		    			       		    			
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
   		    			Log.i("andless", "1");
   		    			String cstats = new String("createNewFile()");
   		    			int i;
   		    			try {
   		    			    boolean append = true;
   		    				if(!plist_file.exists()) {
   		    					plist_file.createNewFile(); append = false;
   		    				}
   		    				cstats = new String("FileWiter()");
   		    				FileWriter fw = new FileWriter(plist_file, append);
   		    				cstats = new String("BufferedWriter()");
   		    				BufferedWriter writer = new BufferedWriter(fw, 8192);
   		    				cstats = new String("write()");
   		    				for(i = 0; i < filez.size(); i++) writer.write(filez.get(i) + "\n");
   		    				cstats = new String("close()");
   		    				writer.close();
       		    			prefs.plist_path = new String(spath);
       		    			prefs.plist_name = new String(sname);
   		    			} catch (Exception e) {
   		    				//Toast.makeText(getApplicationContext(), R.string.strIOError, Toast.LENGTH_SHORT).show();
   		    				showMsg( getString(R.string.strIOError) + " in " + cstats + " while saving playlist to " + plist_file.toString());
   		    				return;
   		    			}
   		    			Log.i("andless", "2");
   		    			dismissDialog(ADD_PLAYLIST_DLG);
   		    			Log.i("andless", "3");
   		    			thisDialog = null;
   			        	}
   			        });
   				add_playlist_dlg.setNegativeButton(R.string.strCancel, new DialogInterface.OnClickListener() {
   			        public void onClick(DialogInterface dialog, int whichButton) {
   			        	dismissDialog(ADD_PLAYLIST_DLG);
   		    			thisDialog = null;
   			        	}
   			        });
   				return add_playlist_dlg.create();	
       			
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
   			/*case EDIT_PLAYLIST_DLG:
	        	AlertDialog.Builder submenu = new AlertDialog.Builder(this);
	        	submenu.setTitle("Actions");
	        	submenu.setItems(new String[] {getString(R.string.strMoveToTop), getString(R.string.strMoveToBottom), getString(R.string.strUp), getString(R.string.strDown), getString(R.string.strDelete), getString(R.string.strSave), getString(R.string.strCancel)}, new DialogInterface.OnClickListener() {
	                public void onClick(DialogInterface dialog, int which) {
	                	
	                }
	            });
	        	return submenu.create();*/
   			case VOLUME_DLG:
   				View VolumeView = factory.inflate(R.layout.volume, null);
   				dialog = new Dialog(this, R.style.Theme_VolumeDialog);
   				dialog.setContentView(VolumeView);
   				dialog.setCanceledOnTouchOutside(true);
   				return dialog;
   			 default:
   				return null;
   			}
		
		}
    	
    	@Override
    	public boolean onOptionsItemSelected(MenuItem item) {
    		switch (item.getItemId()) {
    	 	case R.id.Setup:
    	 		//showDialog(SETTINGS_DLG);
    	 		Intent i = new Intent(this, Preferences.class);
    	 		startActivity(i);
    	     	return true;
    	 	case R.id.Quit:
    	 		ExitFromProgram();
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
    		if(fpath.isDirectory()) return playDir(fpath, null); 
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
			
			// listFiles() may return null in some conditions.
			if(filez == null || filez.length == 0) return null;
		    		
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
   	
    	private boolean playDir(File fpath, String filename) {	// filename is with path 

    		int k = 0;
    		parsed_dir r = parseDir(fpath);
    		if(r == null) {
				Toast.makeText(getApplicationContext(), R.string.strNoFiles, Toast.LENGTH_SHORT).show();
				return false;
			}
    		ArrayList<String> filly = new ArrayList<String>();
			for(int i = r.dirs + r.cues; i < r.filez.length; i++) {
				filly.add(r.filez[i].toString());
				if(filename != null && r.filez[i].toString().equals(filename)) k = i - r.dirs - r.cues;
			}
			return playContents(fpath.toString(),filly,null,null,k,0);
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
    			directoryEntries.add(new IconifiedText("...",dir_icon));	
    			for(int i = 0; i < dirs + cues + flacs; i++) {
    				String s  = filez[i].toString().substring(plen);
    				if(i < dirs) directoryEntries.add(new IconifiedText(s,dir_icon));
    				else if(i < dirs + cues) directoryEntries.add(new IconifiedText(s,cue_icon));
    				else directoryEntries.add(new IconifiedText(s,aud_icon));
    				files.add(filez[i].toString());			
    			}	

    			IconifiedTextListAdapter ita = new IconifiedTextListAdapter(this);
    			ita.setListItems(directoryEntries);
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
    			BufferedReader reader;
				if (checkUTF16(fpath)) {
					reader = new BufferedReader(new InputStreamReader(
							new FileInputStream(fpath), "UTF-16"), 8192);
				} else {
					reader = new BufferedReader(new InputStreamReader(
							new FileInputStream(fpath)), 8192);
				}
	    
    			String line = null;
    			String path = null;
    			if(cur_path != null) {
    				path = cur_path.toString();
    				if(!path.endsWith("/")) path += "/";
    			}
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
	    			if(path != null) {
	    				f = new File(path+line);	// maybe it was a relative path 
	    				if(f.exists() && !f.isDirectory() && hasAudioExt(path+line)) filez.add(path+line);
	    			}
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
    	        Drawable dir_icon = getResources().getDrawable(R.drawable.folder);
    	        Drawable aud_icon = getResources().getDrawable(R.drawable.audio1);
    	        directoryEntries.add(new IconifiedText("...",dir_icon));
        	    for(int i = 0; i < filez.size(); i++) {
        			String s = filez.get(i);
        			files.add(s);
        			int k = s.lastIndexOf('/');
        	       	if(k >= 0) s = s.substring(k+1);
        	       	directoryEntries.add(new IconifiedText(s,aud_icon));
        	       	
        	    }	
    	        
    	        IconifiedTextListAdapter ita = new IconifiedTextListAdapter(this);
    	        ita.setListItems(directoryEntries);
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
    			BufferedReader reader;
				if (checkUTF16(fpath)) {
					reader = new BufferedReader(new InputStreamReader(
							new FileInputStream(fpath), "UTF-16"), 8192);
				} else {
					reader = new BufferedReader(new InputStreamReader(
							new FileInputStream(fpath)), 8192);
				}
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
    	        Drawable dir_icon = getResources().getDrawable(R.drawable.folder);	
    	        Drawable aud_icon = getResources().getDrawable(R.drawable.audio1);
    	        directoryEntries.add(new IconifiedText("...",dir_icon));	
    	        for(int i = 0; i < filez.size(); i++) {
    	        	files.add(filez.get(i));
    	        	track_names.add(namez.get(i));
    	        	start_times.add(timez.get(i));
    	        	directoryEntries.add(new IconifiedText(namez.get(i),aud_icon));
    	        }	
        			
    			IconifiedTextListAdapter ita = new IconifiedTextListAdapter(this);
    			ita.setListItems(directoryEntries);
    			fileList.setAdapter(ita);
    	
    	        return true;
    		
    		} catch (Exception e) {
    	    	log_err("exception in setAdapterFromCue(): " + e.toString());
    	    	return false;
    		}
    	}

    	private Boolean checkUTF16(File fpath) throws IOException
    	{
    		FileInputStream fr = new FileInputStream(fpath);
    		byte[] bytes = new byte[2];
    		if(fr.read(bytes, 0, 2) < 2)
    		{
    			throw new IOException("failed reading file in checkUTF16()");
    		}
    		fr.close();

			// First two bytes are equals to Byte Order Mark
			// for Little Endian (0xFFFE) or Big Endian (0xFEFF).
			return (bytes[0] == -1 && bytes[1] == -2)
					|| (bytes[0] == -2 && bytes[1] == -1);
    	}

       	///////////////////////////////////////////////////////
    	////////////////// Media button events ////////////////
    	
    	/*
    	public final class MediaButtonReceiver extends BroadcastReceiver {
    		@Override
    		public void onReceive(Context context, Intent intent) {
    			Log.i("AndLessSrv.MediaButtonReceiver", "button event: " + intent.getStringExtra(Intent.EXTRA_KEY_EVENT));
    			KeyEvent event = (KeyEvent)  intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    			if(event == null || event.getAction() != KeyEvent.ACTION_DOWN) return;
  	          try {
  	        	 switch(event.getKeyCode()) {
    	        	case KeyEvent.KEYCODE_MEDIA_STOP:
    	        		if(srv!= null) srv.pause();
    	        		break;
            		case KeyEvent.KEYCODE_HEADSETHOOK:
            		case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            			if(srv!= null) {
            				if(srv.is_running() && !srv.is_paused()) srv.pause();
            				else srv.resume();
            			}
            			break;
            		case KeyEvent.KEYCODE_MEDIA_NEXT:
            			if(srv != null) srv.play_next();
            			break;
            		case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            			if(srv != null) srv.play_prev();
            			break;
  	        	 }
    	      } catch(Exception e) {}   
    		}
    	} */	

}

