package net.avs234.iconifiedlist;

import java.util.ArrayList;
import java.util.List;
import net.avs234.R;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

/** @author Steven Osborn - http://steven.bitsetters.com */
public class IconifiedTextListAdapter extends BaseAdapter {
	
		LayoutInflater factory; 

        /** Remember our context so we can use it when constructing views. */
        private Context mContext;

        private List<IconifiedText> mItems = new ArrayList<IconifiedText>();

        public IconifiedTextListAdapter(Context context) {
                mContext = context;
                factory = LayoutInflater.from(context);
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
       
        /** @param convertView The old view to overwrite, if one is passed
         * @returns a IconifiedTextView that holds wraps around an IconifiedText */
        public View getView(int position, View convertView, ViewGroup parent) {
        	
        	View newView = factory.inflate(R.layout.row, null);
                
            TextView txtView = (TextView)newView.findViewById(R.id.title);
            txtView.setText(mItems.get(position).getText());
                	
            ImageView imgView = (ImageView)newView.findViewById(R.id.icon);
            imgView.setImageDrawable(mItems.get(position).getIcon());
                
           return newView;
        }
}
