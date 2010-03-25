package net.avs234;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;

public class Preferences extends PreferenceActivity {

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPreferenceScreen(createPreferenceHierarchy());
    }
    
    @Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case 0:
            return new AlertDialog.Builder(this)
                .setIcon(R.drawable.new_icon)
                .setTitle(R.string.app_name)
                .setMessage(R.string.strAbout)
                .create();
		}
		return null;
	}

    private PreferenceScreen createPreferenceHierarchy() {
        PreferenceScreen root = getPreferenceManager().createPreferenceScreen(this);
        root.setTitle(getString(R.string.app_name)+" "+getString(R.string.strSettings));
        
        PreferenceCategory launchPrefCat = new PreferenceCategory(this);
        launchPrefCat.setTitle(R.string.strBSettings);
        root.addPreference(launchPrefCat);
        
        CheckBoxPreference shuffle_mode = new CheckBoxPreference(this);
        shuffle_mode.setTitle(R.string.strShuffle);
        shuffle_mode.setKey("shuffle_mode");
        launchPrefCat.addPreference(shuffle_mode);
        
        CheckBoxPreference driver_mode = new CheckBoxPreference(this);
        driver_mode.setTitle(R.string.strDriverMode);
        driver_mode.setKey("driver_mode");
        launchPrefCat.addPreference(driver_mode);
        
        CheckBoxPreference book_mode = new CheckBoxPreference(this);
        book_mode.setTitle(R.string.strSaveBooks);
        book_mode.setKey("book_mode");
        launchPrefCat.addPreference(book_mode);
        
        PreferenceCategory andlessPrefCat = new PreferenceCategory(this);
        andlessPrefCat.setTitle(R.string.app_name);
        root.addPreference(andlessPrefCat);
        
        PreferenceScreen andlessPrefAbout = getPreferenceManager().createPreferenceScreen(this);
        andlessPrefAbout.setOnPreferenceClickListener(new OnPreferenceClickListener() {
			
			public boolean onPreferenceClick(Preference p) {
				showDialog(0);
				return false;
			}
        });
        andlessPrefAbout.setTitle(R.string.strAbout1);
        andlessPrefCat.addPreference(andlessPrefAbout);
        return root;
    }
}