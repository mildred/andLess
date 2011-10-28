andLess
=======

andLess was converted from subversion and downloaded from: https://code.google.com/p/andless/

This README is from: https://code.google.com/p/andless/wiki/Building

Prerequisites
-------------

- Android SDK and NDK v.1.6r1 or later
- JRE & JDK 1.6
- Eclipse 3.5
- ADT plug-in for Eclipse, v0.9.5 or later
- GNU make 

Details
-------

The following assumes that your operating system is Linux. I didn't try this in Windows, but I think it should be straightforward.

1.  Install and setup the above tools if you didn't do it yet.

2.  Download the sources from this SVN:

        svn checkout http://andless.googlecode.com/svn/trunk andLess

3.  Go to your NDK root directory

4.  `cd apps/lossless`

5.  Create the file "apps/lossless/Application.mk" containing two lines:

        APP_PROJECT_PATH := $(call my-dir)/project
        APP_MODULES      := alac ape flac wav wv mpc lossless

6.  In apps/lossless, make a link called "project" pointing to the directory where you've unpacked the andLess sources (full path)

7.  For NDK version 1.6, change the following line in build-binary.mk

        diff -r android-ndk-1.6_r1.orig/build/core/build-binary.mk android-ndk-1.6_r1/build/core/build-binary.mk
        166c166
        < $(LOCAL_BUILT_MODULE): PRIVATE_STATIC_LIBRARIES := $(static_libraries)
        ---
        > $(LOCAL_BUILT_MODULE): PRIVATE_WHOLE_STATIC_LIBRARIES := $(static_libraries)

8.  Go to the NDK root dir and "make APP=lossless" to get "liblossless.so" in its proper place, so that it'll get added to the .apk when you build the java code.

9.  Import the Java part of this project to Eclipse and build it. 

You should get an installable apk package containing the library.

Update for Windows users: andLess was reported to compile with ndk-r4 & 5 using http://code.google.com/p/mini-cygwin (thanks vrix yan)

