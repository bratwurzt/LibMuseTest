/**
 * Example of using libmuse library on android. Interaxon, Inc. 2015
 */

package com.interaxon.test.libmuse;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import com.interaxon.libmuse.ConnectionState;
import com.interaxon.libmuse.LibMuseVersion;
import com.interaxon.libmuse.Muse;
import com.interaxon.libmuse.MuseArtifactPacket;
import com.interaxon.libmuse.MuseConnectionListener;
import com.interaxon.libmuse.MuseConnectionPacket;
import com.interaxon.libmuse.MuseDataListener;
import com.interaxon.libmuse.MuseDataPacket;
import com.interaxon.libmuse.MuseDataPacketType;
import com.interaxon.libmuse.MuseManager;
import com.interaxon.libmuse.MusePreset;
import com.interaxon.libmuse.MuseVersion;
import com.interaxon.test.libmuse.runnable.RemoteClientSaveWorker;
import eu.fistar.sdcs.pa.ZephyrProtos;

/**
 * In this simple example MainActivity implements 2 MuseHeadband listeners and updates UI when data from Muse is received. Similarly you can implement listers for
 * other data or register same listener to listen for different type of data. For simplicity we create Listeners as inner classes of MainActivity. We pass reference
 * to MainActivity as we want listeners to update UI thread in this example app. You can also connect multiple muses to the same phone and register same listener to
 * listen for data from different muses. In this case you will have to provide synchronization for data members you are using inside your listener. Usage
 * instructions: 1. Enable bluetooth on your device 2. Pair your device with muse 3. Run this project 4. Press Refresh. It should display all paired Muses in Spinner
 * 5. Make sure Muse headband is waiting for connection and press connect. It may take up to 10 sec in some cases. 6. You should see EEG and accelerometer data as
 * well as connection status, Version information and MuseElements (alpha, beta, theta, delta, gamma waves) on the screen.
 */
public class MainActivity extends Activity implements OnClickListener
{
  private static final Pattern PARTIAl_IP_ADDRESS =
      Pattern.compile("^((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[0-9])\\.){0,3}((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[0-9])){0,1}$");
  protected ExecutorService m_queryThreadExecutor = Executors.newSingleThreadExecutor();
  private Muse muse = null;
  private ConnectionListener connectionListener = null;
  private DataListener dataListener = null;
  private boolean dataTransmission = true;
  private Handler mHandler = null;
  //private File m_observationsFile;
  private KeyStore m_keystore;
  private PowerManager.WakeLock m_wakeLock;
  private EditText m_ipAddressEditText, m_portEditText;
  private TextView m_sendLocationsText;
  private ConnectivityManager m_connManager;

  public MainActivity()
  {
    // Create listeners and pass reference to activity to them
    WeakReference<Activity> weakActivity = new WeakReference<Activity>(this);
    connectionListener = new ConnectionListener(weakActivity);
    dataListener = new DataListener(weakActivity);
    new Thread(dataListener).start();
    new Thread(new HeapUsageLogger()).start();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    m_connManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
    PowerManager m_pwrManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
    m_wakeLock = m_pwrManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakeLock");

    Button refreshButton = (Button)findViewById(R.id.refresh);
    refreshButton.setOnClickListener(this);
    Button connectButton = (Button)findViewById(R.id.connect);
    connectButton.setOnClickListener(this);
    Button disconnectButton = (Button)findViewById(R.id.disconnect);
    disconnectButton.setOnClickListener(this);
    Button pauseButton = (Button)findViewById(R.id.pause);
    pauseButton.setOnClickListener(this);
    Button sendOscButton = (Button)findViewById(R.id.send_osc);
    sendOscButton.setOnClickListener(this);
    m_ipAddressEditText = (EditText)findViewById(R.id.ip_address_edit_text);
    m_sendLocationsText = (TextView)findViewById(R.id.send_locations);
    m_portEditText = (EditText)findViewById(R.id.port_edit_text);
    m_ipAddressEditText.addTextChangedListener(new TextWatcher()
    {
      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count)
      {
      }

      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after)
      {
      }

      private String mPreviousText = "";

      @Override
      public void afterTextChanged(Editable s)
      {
        if (PARTIAl_IP_ADDRESS.matcher(s).matches())
        {
          mPreviousText = s.toString();
        }
        else
        {
          s.replace(0, s.length(), mPreviousText);
        }
      }
    });

    try
    {
      AssetManager assetMgr = getAssets();
      InputStream clientJksInputStream = assetMgr.open("client.bks");
      m_keystore = KeyStore.getInstance("BKS");
      m_keystore.load(clientJksInputStream, "klient1".toCharArray());
    }
    catch (IOException e)
    {
      e.printStackTrace();
    }
    catch (KeyStoreException e)
    {
      e.printStackTrace();
    }
    catch (NoSuchAlgorithmException e)
    {
      e.printStackTrace();
    }
    catch (CertificateException e)
    {
      e.printStackTrace();
    }

    Log.i("Muse Headband", "libmuse version=" + LibMuseVersion.SDK_VERSION);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState)
  {
    super.onSaveInstanceState(outState);
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
    m_wakeLock.release();
  }

  @Override
  protected void onDestroy()
  {
    super.onDestroy();
    mHandler.getLooper().quit();
    m_wakeLock.release();
  }

  @Override
  public void onClick(View v)
  {
    Spinner musesSpinner = (Spinner)findViewById(R.id.muses_spinner);
    if (v.getId() == R.id.refresh)
    {
      MuseManager.refreshPairedMuses();
      List<Muse> pairedMuses = MuseManager.getPairedMuses();
      List<String> spinnerItems = new ArrayList<>();
      for (Muse m : pairedMuses)
      {
        String dev_id = m.getName() + "-" + m.getMacAddress();
        Log.i("Muse Headband", dev_id);
        spinnerItems.add(dev_id);
      }
      ArrayAdapter<String> adapterArray = new ArrayAdapter<>(
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
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING)
        {
          Log.w("Muse Headband", "doesn't make sense to connect second time to the same muse");
          return;
        }
        configureLibrary();

        /**
         * In most cases libmuse native library takes care about
         * exceptions and recovery mechanism, but native code still
         * may throw in some unexpected situations (like bad bluetooth
         * connection). Print all exceptions here.
         */
        try
        {
          muse.runAsynchronously();
          m_wakeLock.acquire();
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
        m_wakeLock.release();
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
    //else if (v.getId() == R.id.send_osc)
    //{
    //  String ipAddress = m_ipAddressEditText.getText().toString();
    //  Integer port = Integer.parseInt(m_portEditText.getText().toString());
    //  DataSender dataSender = new DataSender(ipAddress, port);
    //  m_dataSenderMap.put(ipAddress, dataSender);
    //  new Thread(dataSender).start();
    //
    //  for (DataSender sender : m_dataSenderMap.values())
    //  {
    //    m_sendLocationsText.append(sender.getIpAddress() + ":" + sender.getPort() + "\n");
    //  }
    //}
  }

  private void configureLibrary()
  {
    muse.registerConnectionListener(connectionListener);
    muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
    //muse.registerDataListener(dataListener, MuseDataPacketType.DROPPED_EEG);
    //muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);
    //muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
    //muse.registerDataListener(dataListener, MuseDataPacketType.DROPPED_ACCELEROMETER);
    muse.registerDataListener(dataListener, MuseDataPacketType.ARTIFACTS);
    //muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
    muse.registerDataListener(dataListener, MuseDataPacketType.HORSESHOE);
    muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
    //muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.BETA_RELATIVE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.DELTA_RELATIVE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.THETA_RELATIVE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_RELATIVE);
    muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.BETA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.DELTA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.THETA_ABSOLUTE);
    muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_ABSOLUTE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_SCORE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.BETA_SCORE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.DELTA_SCORE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.THETA_SCORE);
    //muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_SCORE);
    muse.registerDataListener(dataListener, MuseDataPacketType.MELLOW);
    muse.registerDataListener(dataListener, MuseDataPacketType.CONCENTRATION);
    muse.setPreset(MusePreset.PRESET_12);
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
    return id == R.id.action_settings || super.onOptionsItemSelected(item);
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
            TextView statusText = (TextView)findViewById(R.id.con_status);
            statusText.setText(status);
            TextView museVersionText = (TextView)findViewById(R.id.version);
            if (current == ConnectionState.CONNECTED)
            {
              MuseVersion museVersion = muse.getMuseVersion();
              museVersionText.setText(museVersion.getFirmwareType() +
                      " - " + museVersion.getFirmwareVersion() +
                      " - " + Integer.toString(museVersion.getProtocolVersion())
              );
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

  class HeapUsageLogger implements Runnable
  {
    @Override
    public void run()
    {
      while (true)
      {
        logHeapUsage();
        try
        {
          Thread.sleep(5000);
        }
        catch (InterruptedException e)
        {
          Log.e("Muse Heap", e.toString());
        }
      }
    }

    public synchronized void logHeapUsage()
    {
      Runtime runtime = Runtime.getRuntime();
      long total = runtime.totalMemory() / 1024;
      long free = runtime.freeMemory() / 1024;
      Log.d("Muse Heap", "Heap(KB) total: " + total + ", free: " + free + " used: " + (total - free));
    }
  }

  /**
   * Data listener will be registered to listen for: Accelerometer, Eeg and Relative Alpha bandpower packets. In all cases we will update UI with new values. We also
   * will log message if Artifact packets contains "blink" flag. DataListener methods will be called from execution thread. If you are implementing "serious"
   * processing algorithms inside those listeners, consider to create another thread.
   */
  class DataListener extends MuseDataListener implements Runnable
  {
    final WeakReference<Activity> activityRef;
    private ZephyrProtos.ObservationsPB.Builder m_builder;

    protected DataListener(final WeakReference<Activity> activityRef)
    {
      this.activityRef = activityRef;
      m_builder = ZephyrProtos.ObservationsPB.newBuilder();
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
      List<String> values = new ArrayList<>();
      for (Double val : p.getValues())
      {
        values.add(Double.toString(val));
      }
      m_builder.addObservations(
          ZephyrProtos.ObservationPB.newBuilder()
              .setName(p.getPacketType().toString())
              .setUnit("")
              .setTime(p.getTimestamp() / 1000)
              .addAllValues(values)
      );

      if (m_builder.getObservationsCount() >= 500)
      {
        NetworkInfo activeNetInfo = m_connManager.getActiveNetworkInfo();
        if ("WIFI".equals(activeNetInfo.getTypeName()))
        {
          m_queryThreadExecutor.execute(new RemoteClientSaveWorker(m_builder.build(), m_keystore));
          m_builder.clear();
        }
      }
    }

    @Override
    public void receiveMuseArtifactPacket(MuseArtifactPacket p)
    {
      if (p.getBlink())
      {
        m_builder.addObservations(
            ZephyrProtos.ObservationPB.newBuilder()
                .setName("ARTIFACTS")
                .setUnit("")
                .setTime(System.currentTimeMillis())
                .addValues("blink")
        );
      }
      if (p.getJawClench())
      {
        m_builder.addObservations(
            ZephyrProtos.ObservationPB.newBuilder()
                .setName("ARTIFACTS")
                .setUnit("")
                .setTime(System.currentTimeMillis())
                .addValues("jaw")
        );
      }

      if (m_builder.getObservationsCount() >= 500)
      {
        NetworkInfo activeNetInfo = m_connManager.getActiveNetworkInfo();
        if ("WIFI".equals(activeNetInfo.getTypeName()))
        {
          m_queryThreadExecutor.execute(new RemoteClientSaveWorker(m_builder.build(), m_keystore));
          m_builder.clear();
        }
      }
    }
  }
}
