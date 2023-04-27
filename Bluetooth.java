package accordionfactory.com.concertoda_300;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Created by Rob on 3/7/2018.
 */

public class Bluetooth {
    private static Bluetooth instance;
    public static Bluetooth getInstance(Context context) {
        if (instance == null)
            instance = new Bluetooth(context);
        instance.bootloader = null;
        return instance;
    }
    public static Bluetooth getInstance(Context context, Object abootloader) {
        instance = getInstance(context);
        instance.bootloader = abootloader;
        return instance;
    }

    private Object bootloader = null;
    private Context context = null;
    private final String TAG = Bluetooth.class.getSimpleName();
//    private Handler mHandler; // Our main handler that will receive callback notifications
    private ConnectedThread mConnectedThread; // bluetooth background worker thread to send and receive data
    private BluetoothSocket mBTSocket = null; // bi-directional client-to-client data path
    private final BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private final ArrayAdapter<String> mBTArrayAdapter;
    private String address;
    private String deviceName;
    private boolean deviceConnected = false;
    private boolean cancelled = false;
    private boolean dontReconnect = false;
    private final OnBluetooth onBluetooth;
    private BinUploader binUploader = null;
    private boolean binUploaderActive = false;

    public void setBinUploader(BinUploader abinUpLoader) {
        binUploader = abinUpLoader;
        binUploaderActive = true;
    }
    public void clearBinUploader() {
        binUploader = null;
        binUploaderActive = false;
    }

    private static final UUID BTMODULEUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // "random" unique identifier

    // #defines for identifying shared types between calling functions
    private final static int REQUEST_ENABLE_BT = 1; // used to identify adding bluetooth names
    private final static int MESSAGE_READ = 2; // used in bluetooth handler to identify message update
    private final static int CONNECTING_STATUS = 3; // used in bluetooth handler to identify message status

    public String getDeviceName() { return deviceName; }
    public boolean getDeviceConnected() { return deviceConnected; }
    public boolean getBluetoothEnabled() { return mBTAdapter.isEnabled();}

    public void cancelConnection() {
        cancelled = true;
        if (mConnectedThread != null)
            mConnectedThread.cancel(); }

    public void setDontReconnect(boolean value) {
        dontReconnect = value;
    }

    public void write(byte[] data, boolean needsAck, boolean isBin) {
        if (mConnectedThread != null) {
            if (isBin || !binUploaderActive)
                mConnectedThread.write(data, needsAck);
        }
    }

    public void write(byte[] data, boolean needsAck) {
        write(data, needsAck, false);
    }

    public void write(byte[] data) {
        write(data, false, false);
    }

    public void processAck(boolean ack) {
        if (mConnectedThread != null)
            mConnectedThread.processAck(ack);
    }

    private Bluetooth(Context context) {
        this.context = context;
        onBluetooth = (OnBluetooth) context;
        mBTArrayAdapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1);
        mBTAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio

        // Ask for location permission if not already allowed
        if(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions((Activity)context ,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);

        address = Global.getInstance(context).getConcertoBluetoothAddress();
        if (address.length() > 0) {
            cancelConnection();
            Connect();
        }
        else {
            displayPairedDevices();
        }
    }

    public void displayPairedDevices() {
        cancelled = true;
        if (deviceConnected) {
            cancelConnection();
        }

        listPairedDevices();
        ListView mDevicesListView = new ListView(context);
        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        builder.setView(mDevicesListView);

        builder.setMessage("Connect Bluetooth to Concerto");

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);

        dialog.show();
        final Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        save.setVisibility(View.GONE);
        mDevicesListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                String info = ((TextView) v).getText().toString();
                address = info.substring(info.length() - 17);
                Global global = Global.getInstance(context);
                global.setConcertoBluetoothAddress(address);
                DataModule.getInstance(context).SaveGlobalSettings(global);
                Connect();

                ((OnDisplayMessage)context).onClearMessage();
                dialog.dismiss();
            }
        });
    }

    private void listPairedDevices(){
        mPairedDevices = mBTAdapter.getBondedDevices();
        if(mBTAdapter.isEnabled()) {
            mBTArrayAdapter.clear();
            for (BluetoothDevice device : mPairedDevices)
                mBTArrayAdapter.add(device.getName() + "--" + device.getAddress());
        }
        else
            Toast.makeText(context, "Bluetooth not on", Toast.LENGTH_LONG).show();
    }

    public void Connect() {
        if(!mBTAdapter.isEnabled()) {
            Toast.makeText(context, "Bluetooth not on", Toast.LENGTH_LONG).show();
            return;
        }

        if (this.address == "") {
            Toast.makeText(context, "No Device to connect to", Toast.LENGTH_LONG).show();
            return;
        }

        cancelled = false;

//        mBluetoothStatus.setText("Connecting...");
        // Get the device MAC address
        final String address = this.address;

        // Spawn a new thread to avoid blocking the GUI one
        new Thread()
        {
            public void run() {
                boolean fail;

                BluetoothDevice device = mBTAdapter.getRemoteDevice(address);
                deviceName = device.getName();
                onBluetooth.setBlueToothName(deviceName);

                deviceConnected = false;
                while (!deviceConnected) {
                    if (cancelled)
                        break;

                    while (!cancelled && dontReconnect) {}

                    fail = false;

                    try {
                        mBTSocket = createBluetoothSocket(device);
                        mBTSocket.
                    } catch (IOException e) {
                        fail = true;
                        Toast.makeText(context, "Socket creation failed", Toast.LENGTH_LONG).show();
                    }
                    // Establish the Bluetooth socket connection.
                    try {
                        if (!fail)
                            mBTSocket.connect();
                    } catch (IOException e) {
                        try {
                            fail = true;
                            mBTSocket.close();
                            AndroidMessageQueue.getInstance().Add(new BluetoothMessage(e.getMessage()));
//                        mHandler.obtainMessage(CONNECTING_STATUS, -1, -1)
//                                .sendToTarget();
                        } catch (IOException e2) {
                            //insert code to deal with this
                            AndroidMessageQueue.getInstance().Add(new BluetoothMessage(e2.getMessage()));
                            Toast.makeText(context, "Socket creation failed", Toast.LENGTH_LONG).show();
                        }
                    }
                    if (!fail) {
                        mConnectedThread = new ConnectedThread(mBTSocket);
                        mConnectedThread.start();

                        deviceConnected = true;
                        AndroidMessageQueue.getInstance().Add(new BluetoothMessage("Connected to " +
                                deviceName + " -- " + address));
                        onBluetooth.sendReset();
                        onBluetooth.onBlueToothConnect(!fail);
                    } else
                        deviceConnected = false;

                    try {
                        if (!deviceConnected)
                            Thread.sleep(5000);
                    } catch (InterruptedException e) {}
                }
            }
        }.start();
    }

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        try {
            final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
            return (BluetoothSocket) m.invoke(device, BTMODULEUUID);
        } catch (Exception e) {
            Log.e(TAG, "Could not create Insecure RFComm Connection",e);
        }
        return  device.createRfcommSocketToServiceRecord(BTMODULEUUID);
    }

    private class MessageToSend {
        public byte[] msg;
        public boolean needsAck;
        public int resends;

        public MessageToSend(byte[] aMsg, boolean aNeedsAck) {
            msg = aMsg;
            needsAck = aNeedsAck;
            resends = 0;
        }

        public void resend() {
            resends++;
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final List<MessageToSend> msgQueue;
        private boolean waitingAck = false;
        long ackRequested = -1;
        long lastAck = 0;
        private boolean processingMessageQueue = false;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            msgQueue = Collections.synchronizedList(new ArrayList<MessageToSend>());
            long msgAckRequested = -1;
            boolean waitingForAck = false;
        }

        public void run() {
            int bytes; // bytes returned from read()
            byte[] buffer = new byte[512];
            int offset = 0, start, end, len;
            long dataReceived = -1;
            boolean isRegisterTransfer;
            boolean isCRC;

            new Thread() {
                @Override
                public void run() {
                    while (!cancelled) {
                        if (!processingMessageQueue)
                            processMessageQueue();
                    }
                }
            }.start();

            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    if (!mmSocket.isConnected()) {
                        onBluetooth.onBlueToothConnect(false);
                        break;
                    }

                    // Read from the InputStream
                    bytes = mmInStream.available();
                    if(bytes != 0) {
                        dataReceived = System.nanoTime();
                        bytes = mmInStream.available(); // how many bytes are ready to be read?
                        if (bytes > (512-offset))
                            bytes = 512-offset;
                        bytes = mmInStream.read(buffer, offset, bytes); // record how many bytes we actually read
                        len = offset + bytes;
                        if (buffer[0] != -16) {
                            for (int i = 1; i < bytes + offset; i++)
                                if (buffer[i] == -16) {
                                    byte[] tmp = new byte[512];
                                    len -= i;
                                    System.arraycopy(buffer, i, tmp, 0, len);
                                    buffer = tmp;
                                }
                            if (buffer[0] != -16) { //no start command in buffer, throw away data
                                offset = 0;
                                continue;
                            }
                        }

                        start = 0;
                        while (len > 0) {
                            end = start;

                            isRegisterTransfer = (buffer[start + 1] == 3) ||
                                    (buffer[start + 1] == 10) ||
                                    (buffer[start + 1] == 11);

                            isCRC = (buffer[start + 1] == 5) ||
                                    (buffer[start + 1] == 6);

                            if (isRegisterTransfer) {
                                if (len >= 168)
                                    end = start + 167;  //Point at last byte of command
                            } else if (isCRC) {
                                if (len >= 6)
                                    end = start + 5; //Point at last byte of command
                            }  else  {
                                for (int i = start + 1; i < len + start; i++)
                                    if (buffer[i] == -9) {
                                        end = i;
                                        break;
                                    }
                            }
                            if (end > start) {
                                byte[] msg = new byte[end - start + 1];
                                System.arraycopy(buffer, start, msg, 0, end - start + 1);
                                BluetoothMessage bm = new BluetoothMessage(msg, BluetoothMessage.Direction.IN);
                                onBluetooth.setReceivedData(bm);
                                if (binUploader != null)
                                    binUploader.setReceivedData(bm);
                                AndroidMessageQueue.getInstance().Add(bm);
//                                onBluetooth.displayData(msg.length, 0, msg);
                                len = len - end + start - 1;
                                start = end + 1;
                                if (len == 0)
                                    offset = 0;
                            } else {
                                if (start > 0) {
                                    byte[] tmp = new byte[512];
                                    System.arraycopy(buffer, start, tmp, 0, len);
                                    buffer = tmp;
                                }
                                offset = len;
                                len = 0;
//                                onBluetooth.displayData(offset, 2, buffer);  //debugging -- display incomplete msgs
                            }
                        }
                    }
                    else {
                        if ((dataReceived > -1) && (System.nanoTime() - dataReceived > 60000000000L)
                            && !binUploaderActive)
                                break;
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                    AndroidMessageQueue.getInstance().Add(new BluetoothMessage(e.getMessage()));
                    break;
                }
            }
            try {
                onBluetooth.onBlueToothConnect(false);
                mmSocket.close();
            } catch (IOException e) {}
            if (!cancelled) {
                Connect();
            }
        }

        private void processMessageQueue() {
            processingMessageQueue = true;
            try {
                MessageToSend msg;

                if (waitingAck) {
                    if ((ackRequested > -1) && (System.nanoTime() - ackRequested > 2000000000)) {
                        synchronized(msgQueue) {
                            try {
                                msg = msgQueue.get(0);
                                if (msg.resends < 4) {
                                    doWrite(msg.msg);
                                    ackRequested = System.nanoTime();
                                    msg.resend();
                                } else {
                                    waitingAck = false;
                                    msgQueue.remove(0);
                                }
                            } catch (java.lang.IndexOutOfBoundsException e) {}
                        }
                    }
                } else {
                    while (!waitingAck && !msgQueue.isEmpty()) {
                        synchronized (msgQueue) {
                            try {
                                //If we send another message too quickly after the last ack, we don't get
                                //the next ack, and we have to wait for the timeout value of 2 seconds and
                                //then send again. Waiting 100 milliseconds resolves the issue.
                                //50 milliseconds did not have as good a result
                                while (System.nanoTime() - lastAck < 100000000) {}
                                if (!msgQueue.isEmpty()) { //for some reason we're coming into this block even when empty
                                    msg = msgQueue.get(0);

                                    waitingAck = msg.needsAck;
                                    doWrite(msg.msg);
                                }
                                if (waitingAck)
                                    ackRequested = System.nanoTime();
                                else
                                    msgQueue.remove(0); //do not remove message if it needs an ack
                            } catch (java.lang.IndexOutOfBoundsException e) {}
                        }
                    }
                }
            } finally {
                processingMessageQueue = false;
            }

        }

        /* Call this from the main activity to send data to the remote device */
        public void write(byte[] bytes, boolean ackExpected) {
            MessageToSend msg;
            if (bytes!= null) {
                  msgQueue.add(new MessageToSend(bytes, ackExpected));
            }
        }

        private void write(byte[] bytes) {
            write(bytes, false);
        }

        private void doWrite(byte[] bytes) {
            try {
                BluetoothMessage bm = new BluetoothMessage(bytes, BluetoothMessage.Direction.OUT);
                AndroidMessageQueue.getInstance().Add(bm);
                mmOutStream.write(bytes);
            } catch (IOException e) { }

        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }

        public void processAck(boolean ack) {
            synchronized(msgQueue) {
                if (ack) {
                    waitingAck = false;
                    lastAck = System.nanoTime();
                    if (!msgQueue.isEmpty())
                        msgQueue.remove(0);
                } else {
                    ackRequested = 1; //force timeout to resend message
                }
            }
        }
    }

}
