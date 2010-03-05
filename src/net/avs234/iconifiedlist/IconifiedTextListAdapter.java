package net.avs234.iconifiedlist;


import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

/** @author Steven Osborn - http://steven.bitsetters.com */
public class IconifiedTextListAdapter extends BaseAdapter {

        /** Remember our context so we can use it when constructing views. */
        private Context mContext;

        private List<IconifiedText> mItems = new ArrayList<IconifiedText>();

        public IconifiedTextListAdapter(Context context) {
                mContext = context;
        }

        public void addItem(IconifiedText it) { mItems.add(it); }

        public void setListItems(List<IconifiedText> lit) { mItems = lit; }

        /** @return The number of items in the */
        public int getCount() { return mItems.size(); }

        public Object getItem(int position) { return mItems.get(position); }

        public boolean areAllItemsSelectable() { return false; }

        public boolean isSelectable(int position) {
                return mItems.get(position).isSelectable();
        }

        /** Use the array index as a unique id. */
        public long getItemId(int position) {
                return position;
        }
        
        private float fsize = 16.0f;
        
        /** @param convertView The old view to overwrite, if one is passed
         * @returns a IconifiedTextView that holds wraps around an IconifiedText */
        public View getView(int position, View convertView, ViewGroup parent) {
                IconifiedTextView btv;
                if (convertView == null) {
                        btv = new IconifiedTextView(mContext, mItems.get(position));
                        btv.setTextSize(fsize);
                } else { // Reuse/Overwrite the View passed
                        // We are assuming(!) that it is castable!
                        btv = (IconifiedTextView) convertView;
                        btv.setText(mItems.get(position).getText());
                        btv.setTextSize(fsize);
                        btv.setIcon(mItems.get(position).getIcon());
                }
                return btv;
        }
        public void setFontSize(float f) {
        	fsize = f;
        }
}
