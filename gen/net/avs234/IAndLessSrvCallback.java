/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Original file: /home/subbotin/andLess/src/net/avs234/IAndLessSrvCallback.aidl
 */
package net.avs234;
import java.lang.String;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Binder;
import android.os.Parcel;
public interface IAndLessSrvCallback extends android.os.IInterface
{
/** Local-side IPC implementation stub class. */
public static abstract class Stub extends android.os.Binder implements net.avs234.IAndLessSrvCallback
{
private static final java.lang.String DESCRIPTOR = "net.avs234.IAndLessSrvCallback";
/** Construct the stub at attach it to the interface. */
public Stub()
{
this.attachInterface(this, DESCRIPTOR);
}
/**
 * Cast an IBinder object into an IAndLessSrvCallback interface,
 * generating a proxy if needed.
 */
public static net.avs234.IAndLessSrvCallback asInterface(android.os.IBinder obj)
{
if ((obj==null)) {
return null;
}
android.os.IInterface iin = (android.os.IInterface)obj.queryLocalInterface(DESCRIPTOR);
if (((iin!=null)&&(iin instanceof net.avs234.IAndLessSrvCallback))) {
return ((net.avs234.IAndLessSrvCallback)iin);
}
return new net.avs234.IAndLessSrvCallback.Stub.Proxy(obj);
}
public android.os.IBinder asBinder()
{
return this;
}
public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
{
switch (code)
{
case INTERFACE_TRANSACTION:
{
reply.writeString(DESCRIPTOR);
return true;
}
case TRANSACTION_playItemChanged:
{
data.enforceInterface(DESCRIPTOR);
boolean _arg0;
_arg0 = (0!=data.readInt());
java.lang.String _arg1;
_arg1 = data.readString();
this.playItemChanged(_arg0, _arg1);
return true;
}
case TRANSACTION_errorReported:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
this.errorReported(_arg0);
return true;
}
}
return super.onTransact(code, data, reply, flags);
}
private static class Proxy implements net.avs234.IAndLessSrvCallback
{
private android.os.IBinder mRemote;
Proxy(android.os.IBinder remote)
{
mRemote = remote;
}
public android.os.IBinder asBinder()
{
return mRemote;
}
public java.lang.String getInterfaceDescriptor()
{
return DESCRIPTOR;
}
public void playItemChanged(boolean error, java.lang.String name) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeInt(((error)?(1):(0)));
_data.writeString(name);
mRemote.transact(Stub.TRANSACTION_playItemChanged, _data, null, IBinder.FLAG_ONEWAY);
}
finally {
_data.recycle();
}
}
public void errorReported(java.lang.String name) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(name);
mRemote.transact(Stub.TRANSACTION_errorReported, _data, null, IBinder.FLAG_ONEWAY);
}
finally {
_data.recycle();
}
}
}
static final int TRANSACTION_playItemChanged = (IBinder.FIRST_CALL_TRANSACTION + 0);
static final int TRANSACTION_errorReported = (IBinder.FIRST_CALL_TRANSACTION + 1);
}
public void playItemChanged(boolean error, java.lang.String name) throws android.os.RemoteException;
public void errorReported(java.lang.String name) throws android.os.RemoteException;
}
