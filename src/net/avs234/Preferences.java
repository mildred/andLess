package net.avs234;

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceChangeListener;

public class Preferences extends PreferenceActivity {

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPreferenceScreen(createPreferenceHierarchy());
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
        return root;
    }
}