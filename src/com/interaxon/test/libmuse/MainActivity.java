/**
 * Example of using libmuse library on android. Interaxon, Inc. 2015
 */

package com.interaxon.test.libmuse;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPortOut;
import com.interaxon.libmuse.AnnotationData;
import com.interaxon.libmuse.ConnectionState;
import com.interaxon.libmuse.Eeg;
import com.interaxon.libmuse.LibMuseVersion;
import com.interaxon.libmuse.MessageType;
import com.interaxon.libmuse.Muse;
import com.interaxon.libmuse.MuseArtifactPacket;
import com.interaxon.libmuse.MuseConfiguration;
import com.interaxon.libmuse.MuseConnectionListener;
import com.interaxon.libmuse.MuseConnectionPacket;
import com.interaxon.libmuse.MuseDataListener;
import com.interaxon.libmuse.MuseDataPacket;
import com.interaxon.libmuse.MuseDataPacketType;
import com.interaxon.libmuse.MuseFileFactory;
import com.interaxon.libmuse.MuseFileReader;
import com.interaxon.libmuse.MuseManager;
import com.interaxon.libmuse.MusePreset;
import com.interaxon.libmuse.MuseVersion;

/**
 * In this simple example MainActivity implements 2 MuseHeadband listeners and updates UI when data from Muse is received. Similarly you can implement listers for other data or
 * register same listener to listen for different type of data. For simplicity we create Listeners as inner classes of MainActivity. We pass reference to MainActivity as we want
 * listeners to update UI thread in this example app. You can also connect multiple muses to the same phone and register same listener to listen for data from different muses. In
 * this case you will have to provide synchronization for data members you are using inside your listener. Usage instructions: 1. Enable bluetooth on your device 2. Pair your
 * device with muse 3. Run this project 4. Press Refresh. It should display all paired Muses in Spinner 5. Make sure Muse headband is waiting for connection and press connect. It
 * may take up to 10 sec in some cases. 6. You should see EEG and accelerometer data as well as connection status, Version information and MuseElements (alpha, beta, theta, delta,
 * gamma waves) on the screen.
 */
public class MainActivity extends Activity implements OnClickListener
{
  private volatile boolean m_senderRunning = true;
  private final SimpleDateFormat m_dateFormat = new SimpleDateFormat("yyyyMMddhhmm");
  private Muse muse = null;
  private ConnectionListener connectionListener = null;
  private DataListener dataListener = null;
  private boolean dataTransmission = true;
  private BlockingQueue<MuseDataPacket> m_dataQueue;
  //  private MuseFileWriter fileWriter = null;
  private Handler mHandler = null;
  private DataOscSender m_dataOscSender;

  public MainActivity()
  {
    m_dataQueue = new LinkedBlockingQueue<>();
    // Create listeners and pass reference to activity to them
    WeakReference<Activity> weakActivity = new WeakReference<>(this);
    connectionListener = new ConnectionListener(weakActivity);
    dataListener = new DataListener(weakActivity);
    m_dataOscSender = new DataOscSender("192.168.1.2", 41672);
    new Thread(dataListener).start();
    new Thread(m_dataOscSender).start();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    Button refreshButton = (Button) findViewById(R.id.refresh);
    refreshButton.setOnClickListener(this);
    Button connectButton = (Button) findViewById(R.id.connect);
    connectButton.setOnClickListener(this);
    Button disconnectButton = (Button) findViewById(R.id.disconnect);
    disconnectButton.setOnClickListener(this);
    Button pauseButton = (Button) findViewById(R.id.pause);
    pauseButton.setOnClickListener(this);

    File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
//    fileWriter = MuseFileFactory.getMuseFileWriter(new File(dir, "new_muse_file" + m_dateFormat.format(new Date()) + ".muse"));
    Log.i("Muse Headband", "libmuse version=" + LibMuseVersion.SDK_VERSION);
//    fileWriter.addAnnotationString(1, "MainActivity onCreate");
//    dataListener.setFileWriter(fileWriter);
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    mHandler.getLooper().quit();
  }

  @Override
  protected void onStop()
  {
    super.onStop();
    mHandler.getLooper().quit();
  }

  @Override
  protected void onDestroy()
  {
    super.onDestroy();
    mHandler.getLooper().quit();
  }

  @Override
  public void onClick(View v)
  {
    Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner);
    if (v.getId() == R.id.refresh)
    {
      MuseManager.refreshPairedMuses();
      List<Muse> pairedMuses = MuseManager.getPairedMuses();
      List<String> spinnerItems = new ArrayList<String>();
      for (Muse m : pairedMuses)
      {
        String dev_id = m.getName() + "-" + m.getMacAddress();
        Log.i("Muse Headband", dev_id);
        spinnerItems.add(dev_id);
      }
      ArrayAdapter<String> adapterArray = new ArrayAdapter<String>(
          this, android.R.layout.simple_spinner_item, spinnerItems);
      musesSpinner.setAdapter(adapterArray);
    }
    else if (v.getId() == R.id.connect)
    {
      List<Muse> pairedMuses = MuseManager.getPairedMuses();
      if (pairedMuses.size() < 1 ||
          musesSpinner.getAdapter().getCount() < 1)
      {
        Log.w("Muse Headband", "There is nothing to connect to");
      }
      else
      {
        muse = pairedMuses.get(musesSpinner.getSelectedItemPosition());
        ConnectionState state = muse.getConnectionState();
        if (state == ConnectionState.CONNECTED ||
            state == ConnectionState.CONNECTING)
        {
          Log.w("Muse Headband", "doesn't make sense to connect second time to the same muse");
          return;
        }
        configureLibrary();
//        fileWriter.open();
//        fileWriter.addAnnotationString(1, "Connect clicked");
        /**
         * In most cases libmuse native library takes care about
         * exceptions and recovery mechanism, but native code still
         * may throw in some unexpected situations (like bad bluetooth
         * connection). Print all exceptions here.
         */
        try
        {
          muse.runAsynchronously();
        }
        catch (Exception e)
        {
          Log.e("Muse Headband", e.toString());
        }
      }
    }
    else if (v.getId() == R.id.disconnect)
    {
      if (muse != null)
      {
        /**
         * true flag will force libmuse to unregister all listeners,
         * BUT AFTER disconnecting and sending disconnection event.
         * If you don't want to receive disconnection event (for ex.
         * you call disconnect when application is closed), then
         * unregister listeners first and then call disconnect:
         * muse.unregisterAllListeners();
         * muse.disconnect(false);
         */
        muse.disconnect(true);
//        fileWriter.addAnnotationString(1, "Disconnect clicked");
//        fileWriter.flush();
//        fileWriter.close();
      }
    }
    else if (v.getId() == R.id.pause)
    {
      dataTransmission = !dataTransmission;
      if (muse != null)
      {
        muse.enableDataTransmission(dataTransmission);
      }
    }
  }

  /*
   * Simple example of getting data from the "*.muse" file
   */
  private void playMuseFile(String name)
  {
    File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
    File file = new File(dir, name);
    final String tag = "Muse File Reader";
    if (!file.exists())
    {
      Log.w(tag, "file doesn't exist");
      return;
    }
    MuseFileReader fileReader = MuseFileFactory.getMuseFileReader(file);
    while (fileReader.gotoNextMessage())
    {
      MessageType type = fileReader.getMessageType();
      int id = fileReader.getMessageId();
      long timestamp = fileReader.getMessageTimestamp();
      Log.i(tag, "type: " + type.toString() +
          " id: " + Integer.toString(id) +
          " timestamp: " + String.valueOf(timestamp));
      switch (type)
      {
        case EEG:
        case BATTERY:
        case ACCELEROMETER:
        case QUANTIZATION:
          MuseDataPacket packet = fileReader.getDataPacket();
          Log.i(tag, "data packet: " + packet.getPacketType().toString());
          break;
        case VERSION:
          MuseVersion version = fileReader.getVersion();
          Log.i(tag, "version" + version.getFirmwareType());
          break;
        case CONFIGURATION:
          MuseConfiguration config = fileReader.getConfiguration();
          Log.i(tag, "config" + config.getBluetoothMac());
          break;
        case ANNOTATION:
          AnnotationData annotation = fileReader.getAnnotation();
          Log.i(tag, "annotation" + annotation.getData());
          break;
        default:
          break;
      }
    }
  }

  private void configureLibrary()
  {
    muse.registerConnectionListener(connectionListener);
    muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
    muse.registerDataListener(dataListener, MuseDataPacketType.DROPPED_EEG);
    muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);
    muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
    muse.registerDataListener(dataListener, MuseDataPacketType.DROPPED_ACCELEROMETER);
    muse.registerDataListener(dataListener, MuseDataPacketType.ARTIFACTS);
    muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
    muse.registerDataListener(dataListener, MuseDataPacketType.HORSESHOE);
    muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
    muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
    muse.registerDataListener(dataListener, MuseDataPacketType.BETA_RELATIVE);
    muse.registerDataListener(dataListener, MuseDataPacketType.DELTA_RELATIVE);
    muse.registerDataListener(dataListener, MuseDataPacketType.THETA_RELATIVE);
    muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_RELATIVE);
    muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.BETA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.DELTA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.THETA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_SCORE);
    muse.registerDataListener(dataListener, MuseDataPacketType.BETA_SCORE);
    muse.registerDataListener(dataListener, MuseDataPacketType.DELTA_SCORE);
    muse.registerDataListener(dataListener, MuseDataPacketType.THETA_SCORE);
    muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_SCORE);
    muse.registerDataListener(dataListener, MuseDataPacketType.MELLOW);
    muse.registerDataListener(dataListener, MuseDataPacketType.CONCENTRATION);
    muse.setPreset(MusePreset.PRESET_14);
    muse.enableDataTransmission(dataTransmission);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu)
  {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item)
  {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();
    if (id == R.id.action_settings)
    {
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /**
   * Connection listener updates UI with new connection status and logs it.
   */
  class ConnectionListener extends MuseConnectionListener
  {
    final WeakReference<Activity> activityRef;

    ConnectionListener(final WeakReference<Activity> activityRef)
    {
      this.activityRef = activityRef;
    }

    @Override
    public void receiveMuseConnectionPacket(MuseConnectionPacket p)
    {
      final ConnectionState current = p.getCurrentConnectionState();
      final String status = p.getPreviousConnectionState().toString() + " -> " + current;
      final String full = "Muse " + p.getSource().getMacAddress() + " " + status;
      Log.i("Muse Headband", full);
      Activity activity = activityRef.get();
      // UI thread is used here only because we need to update
      // TextView values. You don't have to use another thread, unless
      // you want to run disconnect() or connect() from connection packet
      // handler. In this case creating another thread is required.
      if (activity != null)
      {
        activity.runOnUiThread(new Runnable()
        {
          @Override
          public void run()
          {
            TextView statusText = (TextView) findViewById(R.id.con_status);
            statusText.setText(status);
            TextView museVersionText = (TextView) findViewById(R.id.version);
            if (current == ConnectionState.CONNECTED)
            {
              MuseVersion museVersion = muse.getMuseVersion();
              String version = museVersion.getFirmwareType() +
                  " - " + museVersion.getFirmwareVersion() +
                  " - " + Integer.toString(
                  museVersion.getProtocolVersion());
              museVersionText.setText(version);
            }
            else
            {
              museVersionText.setText(R.string.undefined);
            }
          }
        });
      }
    }
  }

  class DataOscSender implements Runnable
  {
    protected OSCPortOut m_oscSender;

    public DataOscSender(String ipAddress, int port)
    {
      try
      {
        m_oscSender = new OSCPortOut(InetAddress.getByName(ipAddress), port);
      }
      catch (SocketException | UnknownHostException e)
      {
        Log.e("OscSender", e.toString());
      }
    }

    @Override
    public void run()
    {
      while (m_senderRunning)
      {
        try
        {
          MuseDataPacket p = m_dataQueue.take();
          switch (p.getPacketType())
          {
            case EEG:
              m_oscSender.send(new OSCMessage("/muse/eeg", getFloats(p)));
              break;
            case QUANTIZATION:
              m_oscSender.send(new OSCMessage("/muse/eeg/quantization", getFloats(p)));
              break;
            case DROPPED_EEG:
              int droppedEeg = (int) p.getValues().get(0).doubleValue();
              m_oscSender.send(new OSCMessage("/muse/eeg/dropped_samples", Collections.singleton(droppedEeg)));
              break;
            case ACCELEROMETER:
              m_oscSender.send(new OSCMessage("/muse/acc", getFloats(p)));
              break;
            case DROPPED_ACCELEROMETER:
              int droppedAcc = (int) p.getValues().get(0).doubleValue();
              m_oscSender.send(new OSCMessage("/muse/acc/dropped_samples", Collections.singleton(droppedAcc)));
              break;
            case DRL_REF:
              m_oscSender.send(new OSCMessage("/muse/drlref", getFloats(p)));
              break;
            case HORSESHOE:
              m_oscSender.send(new OSCMessage("/muse/elements/horseshoe", getFloats(p)));
              break;
            case ARTIFACTS:
              m_oscSender.send(new OSCMessage("/muse/drlref", Collections.singleton(1)));
              break;
            case ALPHA_RELATIVE:
              m_oscSender.send(new OSCMessage("/muse/elements/alpha_relative", getFloats(p)));
              break;
            case BETA_RELATIVE:
              m_oscSender.send(new OSCMessage("/muse/elements/beta_relative", getFloats(p)));
              break;
            case DELTA_RELATIVE:
              m_oscSender.send(new OSCMessage("/muse/elements/delta_relative", getFloats(p)));
              break;
            case THETA_RELATIVE:
              m_oscSender.send(new OSCMessage("/muse/elements/theta_relative", getFloats(p)));
              break;
            case GAMMA_RELATIVE:
              m_oscSender.send(new OSCMessage("/muse/elements/gamma_relative", getFloats(p)));
              break;
            case ALPHA_ABSOLUTE:
              m_oscSender.send(new OSCMessage("/muse/elements/alpha_absolute", getFloats(p)));
              break;
            case BETA_ABSOLUTE:
              m_oscSender.send(new OSCMessage("/muse/elements/beta_absolute", getFloats(p)));
              break;
            case DELTA_ABSOLUTE:
              m_oscSender.send(new OSCMessage("/muse/elements/delta_absolute", getFloats(p)));
              break;
            case THETA_ABSOLUTE:
              m_oscSender.send(new OSCMessage("/muse/elements/theta_absolute", getFloats(p)));
              break;
            case GAMMA_ABSOLUTE:
              m_oscSender.send(new OSCMessage("/muse/elements/gamma_absolute", getFloats(p)));
              break;
            case ALPHA_SCORE:
              m_oscSender.send(new OSCMessage("/muse/elements/alpha_session_score", getFloats(p)));
              break;
            case BETA_SCORE:
              m_oscSender.send(new OSCMessage("/muse/elements/beta_session_score", getFloats(p)));
              break;
            case DELTA_SCORE:
              m_oscSender.send(new OSCMessage("/muse/elements/delta_session_score", getFloats(p)));
              break;
            case THETA_SCORE:
              m_oscSender.send(new OSCMessage("/muse/elements/theta_session_score", getFloats(p)));
              break;
            case GAMMA_SCORE:
              m_oscSender.send(new OSCMessage("/muse/elements/gamma_session_score", getFloats(p)));
              break;
            case MELLOW:
              m_oscSender.send(new OSCMessage("/muse/elements/experimental/mellow", getFloats(p)));
              break;
            case CONCENTRATION:
              m_oscSender.send(new OSCMessage("/muse/elements/experimental/concentration", getFloats(p)));
              break;
            case BATTERY:
              ArrayList<Object> returnList = new ArrayList<>();
              returnList.add((int) (p.getValues().get(0) * 100));
              returnList.add((int) (double) p.getValues().get(1));
              returnList.add((int) (double) p.getValues().get(1));
              returnList.add((int) (double) p.getValues().get(2));
              m_oscSender.send(new OSCMessage("/muse/batt", returnList));
              break;

            default:
              break;
          }
        }
        catch (InterruptedException | IOException e)
        {
          Log.e("Muse Headband", e.toString());
        }
      }
    }

    private ArrayList<Object> getFloats(MuseDataPacket packet)
    {
      ArrayList<Object> returnList = new ArrayList<>();

      for (double value : packet.getValues())
      {
        returnList.add((float) value);
      }
      return returnList;
    }
  }

  /**
   * Data listener will be registered to listen for: Accelerometer, Eeg and Relative Alpha bandpower packets. In all cases we will update UI with new values. We also will log
   * message if Artifact packets contains "blink" flag. DataListener methods will be called from execution thread. If you are implementing "serious" processing algorithms inside
   * those listeners, consider to create another thread.
   */
  class DataListener extends MuseDataListener implements Runnable
  {
    final WeakReference<Activity> activityRef;
//    private MuseFileWriter fileWriter;

    protected DataListener(final WeakReference<Activity> activityRef)
    {
      this.activityRef = activityRef;
    }

    @Override
    public void run()
    {
      Looper.prepare();
      mHandler = new Handler();
      Looper.loop();
    }

    @Override
    public void receiveMuseDataPacket(MuseDataPacket p)
    {
      try
      {
        m_dataQueue.put(p);
      }
      catch (InterruptedException e)
      {
        Log.e("Muse Headband", e.toString());
      }
    }

    @Override
    public void receiveMuseArtifactPacket(MuseArtifactPacket p)
    {
//      if (p.getHeadbandOn() && (p.getBlink() || p.getJawClench()))
//      {
//        try
//        {
//          if (p.getBlink())
//          {
//            m_oscSender.send(new OSCMessage("/muse/elements/blink", Collections.singleton(1)));
//          }
//          if (p.getJawClench())
//          {
//            m_oscSender.send(new OSCMessage("/muse/elements/jaw_clench", Collections.singleton(1)));
//          }
//        }
//        catch (IOException e)
//        {
//          Log.e("Muse Headband", e.toString());
//        }
//      }
    }


    private void updateAlphaRelative(final ArrayList<Double> data)
    {
      Activity activity = activityRef.get();
      if (activity != null)
      {
        activity.runOnUiThread(new Runnable()
        {
          @Override
          public void run()
          {
            TextView elem1 = (TextView) findViewById(R.id.elem1);
            TextView elem2 = (TextView) findViewById(R.id.elem2);
            TextView elem3 = (TextView) findViewById(R.id.elem3);
            TextView elem4 = (TextView) findViewById(R.id.elem4);
            elem1.setText(String.format("%6.2f", data.get(Eeg.TP9.ordinal())));
            elem2.setText(String.format("%6.2f", data.get(Eeg.FP1.ordinal())));
            elem3.setText(String.format("%6.2f", data.get(Eeg.FP2.ordinal())));
            elem4.setText(String.format("%6.2f", data.get(Eeg.TP10.ordinal())));
          }
        });
      }
    }

//    public void setFileWriter(MuseFileWriter fileWriter)
//    {
//      this.fileWriter = fileWriter;
//    }
  }
}
