/**
 * Example of using libmuse library on android. Interaxon, Inc. 2015
 */

package com.interaxon.test.libmuse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPortOut;
import com.interaxon.libmuse.ConnectionState;
import com.interaxon.libmuse.Eeg;
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
import com.interaxon.test.libmuse.filters.IDataFilter;
import com.interaxon.test.libmuse.filters.MeanFilter;
import com.interaxon.test.libmuse.filters.NormalizeMeanFilter;
import com.interaxon.test.libmuse.runnable.LocalClientSaveWorker;
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
  private static String OSC_SENDERS = "OSC_SENDERS";
  protected ExecutorService m_queryThreadExecutor = Executors.newSingleThreadExecutor();
  private Muse muse = null;
  private ConnectionListener connectionListener = null;
  private DataListener dataListener = null;
  private boolean dataTransmission = true;
  private final BlockingQueue<Object> m_dataQueue;
  private final Object m_dataQueueLock = new Object();
  private Handler mHandler = null;
  private Map<String, DataSender> m_dataSenderMap;
  //private File m_observationsFile;
  private KeyStore m_keystore;
  private PowerManager.WakeLock m_wakeLock;
  private EditText m_ipAddressEditText, m_portEditText;
  private TextView m_sendLocationsText;

  public MainActivity()
  {
    m_dataQueue = new LinkedBlockingQueue<>();
    m_dataSenderMap = new HashMap<>();

    // Create listeners and pass reference to activity to them
    WeakReference<Activity> weakActivity = new WeakReference<Activity>(this);
    connectionListener = new ConnectionListener(weakActivity);
    dataListener = new DataListener(weakActivity);
    new Thread(dataListener).start();
    //    new Thread(new HeapUsageLogger()).start();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    //m_observationsFile = new File(getFilesDir(), "observations.dat");
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

    if (m_dataSenderMap == null)
    {
      m_dataSenderMap = new HashMap<>();
    }

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

    if (savedInstanceState != null)
    {
      ArrayList<String> list = savedInstanceState.getStringArrayList(OSC_SENDERS);
      for (int i = 0; i < list.size(); i += 2)
      {
        String ipAddress = list.get(i);
        Integer port = Integer.valueOf(list.get(i + 1));
        DataSender oscSender = new DataSender(ipAddress, port); //todo put processor into stop/pause/kill logic stream
        m_dataSenderMap.put(ipAddress, oscSender);
        if (muse != null && muse.getConnectionState() == ConnectionState.CONNECTED && dataTransmission)
        {
          new Thread(oscSender).start();
          m_sendLocationsText.append(ipAddress + ":" + port + "\n");
        }
      }
    }
    Log.i("Muse Headband", "libmuse version=" + LibMuseVersion.SDK_VERSION);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState)
  {
    super.onSaveInstanceState(outState);
    ArrayList<String> senders = new ArrayList<>();
    for (DataSender sender : m_dataSenderMap.values())
    {
      senders.add(sender.getIpAddress());
      senders.add(sender.getPort());
    }
    outState.putStringArrayList(OSC_SENDERS, senders);
  }

  private void closeSendersAndProcessors()
  {
    for (Iterator<DataSender> iter = m_dataSenderMap.values().iterator(); iter.hasNext(); )
    {
      DataSender sender = iter.next();
      sender.setSenderRunning(false);
      iter.remove();
    }

    m_sendLocationsText.setText("");
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    closeSendersAndProcessors();
    mHandler.getLooper().quit();
  }

  @Override
  protected void onStop()
  {
    super.onStop();
    closeSendersAndProcessors();
    mHandler.getLooper().quit();
  }

  @Override
  protected void onDestroy()
  {
    super.onDestroy();
    closeSendersAndProcessors();
    mHandler.getLooper().quit();
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
    else if (v.getId() == R.id.send_osc)
    {
      String ipAddress = m_ipAddressEditText.getText().toString();
      Integer port = Integer.parseInt(m_portEditText.getText().toString());
      DataSender dataSender = new DataSender(ipAddress, port);
      m_dataSenderMap.put(ipAddress, dataSender);
      new Thread(dataSender).start();

      for (DataSender sender : m_dataSenderMap.values())
      {
        m_sendLocationsText.append(sender.getIpAddress() + ":" + sender.getPort() + "\n");
      }
    }
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
          Thread.sleep(2000);
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

  class DataSender implements Runnable
  {
    private volatile boolean m_senderRunning = true;
    protected String m_ipAddress, m_port;
    private ArrayList<Object> m_floats;
    private ArrayList<Object> m_sendList;

    public DataSender(String ipAddress, int port)
    {
      m_ipAddress = ipAddress;
      m_port = String.valueOf(port);
    }

    @Override
    public void run()
    {
      while (m_senderRunning)
      {
        Object obj;
        ZephyrProtos.ObservationsPB.Builder builder = ZephyrProtos.ObservationsPB.newBuilder();
        int i = 0;
        while ((obj = m_dataQueue.poll()) != null && i++ < 100)
        {
          if (obj instanceof MuseDataPacket)
          {
            MuseDataPacket p = (MuseDataPacket)obj;
            List<String> values = new ArrayList<>();
            for (Double val : p.getValues())
            {
              values.add(Double.toString(val));
            }
            builder.addObservations(
                ZephyrProtos.ObservationPB.newBuilder()
                    .setName(p.getPacketType().toString())
                    .setUnit("")
                    .setTime(p.getTimestamp())
                    .addAllValues(values)
            );
          }
          else if (obj instanceof MuseArtifactPacket)
          {
            MuseArtifactPacket p = (MuseArtifactPacket)obj;
            if (p.getBlink())
            {
              builder.addObservations(
                  ZephyrProtos.ObservationPB.newBuilder()
                      .setName("ARTIFACTS")
                      .setUnit("")
                      .setTime(System.currentTimeMillis())
                      .addValues("blink")
              );
            }
            if (p.getJawClench())
            {
              builder.addObservations(
                  ZephyrProtos.ObservationPB.newBuilder()
                      .setName("ARTIFACTS")
                      .setUnit("")
                      .setTime(System.currentTimeMillis())
                      .addValues("jaw")
              );
            }
          }
        }
        ConnectivityManager connManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetInfo = connManager.getActiveNetworkInfo();
        if ("WIFI".equals(activeNetInfo.getTypeName()))
        {
          //ZephyrProtos.ObservationsPB obss = getFileObservations(m_observationsFile);
          //if (obss != null && obss.getObservationsCount() > 0)
          //{
          //  builder.addAllObservations(obss.getObservationsList());
          //  emptyObservationFile();
          //}
          ZephyrProtos.ObservationsPB observationsPB = builder.build();
          m_queryThreadExecutor.execute(new RemoteClientSaveWorker(observationsPB, m_keystore));
        }
      }
    }

    private ZephyrProtos.ObservationsPB getFileObservations(File observationsFile) throws IOException
    {
      return ZephyrProtos.ObservationsPB.parseDelimitedFrom(new FileInputStream(observationsFile));
    }

    //private void emptyObservationFile()
    //{
    //  String string1 = "";
    //  FileOutputStream fos;
    //  try
    //  {
    //    fos = new FileOutputStream(m_observationsFile, false);
    //    FileWriter fWriter;
    //
    //    try
    //    {
    //      fWriter = new FileWriter(fos.getFD());
    //
    //      fWriter.write(string1);
    //      fWriter.flush();
    //      fWriter.close();
    //    }
    //    catch (Exception e)
    //    {
    //      e.printStackTrace();
    //    }
    //    finally
    //    {
    //      fos.getFD().sync();
    //      fos.close();
    //    }
    //  }
    //  catch (Exception e)
    //  {
    //    e.printStackTrace();
    //  }
    //}

    public synchronized void setSenderRunning(boolean senderRunning)
    {
      m_senderRunning = senderRunning;
    }

    public String getIpAddress()
    {
      return m_ipAddress;
    }

    public String getPort()
    {
      return m_port;
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
        //ArrayList<Double> values = p.getValues();
        //switch (p.getPacketType())
        //{
        //  case ALPHA_ABSOLUTE:
        //    m_dataProcessor.putAlphaFp(new Float[]{values.get(Eeg.FP1.ordinal()).floatValue(), values.get(Eeg.FP2.ordinal()).floatValue()});
        //    break;
        //  case BETA_ABSOLUTE:
        //    m_dataProcessor.putBetaFp(new Float[]{values.get(Eeg.FP1.ordinal()).floatValue(), values.get(Eeg.FP2.ordinal()).floatValue()});
        //    break;
        //  case MELLOW:
        //    m_dataProcessor.putMellow(values.get(0).floatValue());
        //    break;
        //  case CONCENTRATION:
        //    m_dataProcessor.putConcentration(values.get(0).floatValue());
        //    break;
        //  default:
        //    break;
        //}
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

    //    @Override
    //    public int onStartCommand(Intent intent, int flags, int startId)
    //    {
    //      Notification noti = new Notification.Builder(mContext)
    //               .setContentTitle("New mail from " + sender.toString())
    //               .setContentText(subject)
    //               .setSmallIcon(R.drawable.new_mail)
    //               .setLargeIcon(aBitmap)
    //               .build();
    //
    //            Intent i=new Intent(this, FakePlayer.class);
    //
    //            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|
    //                       Intent.FLAG_ACTIVITY_SINGLE_TOP);
    //
    //            PendingIntent pi= PendingIntent.getActivity(this, 0,
    //                i, 0);
    //
    //            note.setLatestEventInfo(this, "Fake Player",
    //                                    "Now Playing: \"Ummmm, Nothing\"",
    //                                    pi);
    //            note.flags|=Notification.FLAG_NO_CLEAR;
    //
    //            startForeground(1337, note);
    //      return super.onStartCommand(intent, flags, startId);
    //    }

    //    @Override
    //    public IBinder onBind(Intent intent)
    //    {
    //      return null;
    //    }

    //private void updateAlphaRelative(final ArrayList<Double> data)
    //{
    //  Activity activity = activityRef.get();
    //  if (activity != null)
    //  {
    //    activity.runOnUiThread(new Runnable()
    //    {
    //      @Override
    //      public void run()
    //      {
    //        TextView elem1 = (TextView) findViewById(R.id.ip_address_edit_text);
    //        TextView elem2 = (TextView) findViewById(R.id.elem2);
    //        TextView elem3 = (TextView) findViewById(R.id.elem3);
    //        TextView elem4 = (TextView) findViewById(R.id.elem4);
    //        elem1.setText(String.format("%6.2f", data.get(Eeg.TP9.ordinal())));
    //        elem2.setText(String.format("%6.2f", data.get(Eeg.FP1.ordinal())));
    //        elem3.setText(String.format("%6.2f", data.get(Eeg.FP2.ordinal())));
    //        elem4.setText(String.format("%6.2f", data.get(Eeg.TP10.ordinal())));
    //      }
    //    });
    //  }
    //}

    //    public void setFileWriter(MuseFileWriter fileWriter)
    //    {
    //      this.fileWriter = fileWriter;
    //    }
  }
}
