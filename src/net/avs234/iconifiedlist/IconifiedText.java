package net.avs234.iconifiedlist;


import android.graphics.drawable.Drawable;

/** @author Steven Osborn - http://steven.bitsetters.com */
public class IconifiedText {

        private String mText = "";
        private Drawable mIcon;
        private boolean mSelectable = true;

        public IconifiedText(String text, Drawable bullet) {
                mIcon = bullet;
                mText = text;
        }

        public boolean isSelectable() {
                return mSelectable;
        }

        public void setSelectable(boolean selectable) {
                mSelectable = selectable;
        }

        public String getText() {
                return mText;
        }

        public void setText(String text) {
                mText = text;
        }

        public void setIcon(Drawable icon) {
                mIcon = icon;
        }

        public Drawable getIcon() {
                return mIcon;
        }
     
}
