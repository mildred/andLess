package net.avs234;

oneway interface IAndLessSrvCallback {
    void playItemChanged(boolean error, String name);
	void errorReported(String name);
	void playItemPaused(boolean paused);
}