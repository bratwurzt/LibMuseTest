/**
 * Example of using libmuse library on android. Interaxon, Inc. 2015
 */

package com.interaxon.test.libmuse;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.interaxon.test.libmuse.filters.NormalizeMeanFilter;
import com.lsl.ChannelFormatT;
import com.lsl.LslAndroidJNI;
import com.lsl.StreamInfo;
import com.lsl.StreamOutlet;

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
  private static LslAndroidJNI m_lsl = new LslAndroidJNI();
  static
  {
    System.loadLibrary("lslAndroid");
  }

  public static final String MELLOW = "MELLOW";
  public static final String CONCENTRATION = "CONCENTRATION";
  public static final String VALENCE_GIRALDO_RAMIREZ = "VALENCE_GIRALDO_RAMIREZ";
  public static final String AROUSAL_GIRALDO_RAMIREZ = "AROUSAL_GIRALDO_RAMIREZ";
  public static final String VALENCE_EMIL = "VALENCE_EMIL";
  public static final String AROUSAL_EMIL = "AROUSAL_EMIL";
  private static final int MEAN_SAMPLE_SIZE = 100;

  private static final Pattern PARTIAl_IP_ADDRESS =
      Pattern.compile("^((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[0-9])\\.){0,3}((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[0-9])){0,1}$");
  private static String OSC_SENDERS = "OSC_SENDERS";
  private Muse muse = null;
  private ConnectionListener connectionListener = null;
  private DataListener dataListener = null;
  private boolean dataTransmission = true;
  private final BlockingQueue<MuseDataPacket> m_dataQueue;
  private Handler mHandler = null;
  private Map<String, DataOscSender> m_dataOscSenderMap;
  private final DataProcessor m_dataProcessor;

  private EditText m_ipAddressEditText, m_portEditText;
  private TextView m_sendLocationsText;

  public MainActivity()
  {
    m_dataQueue = new LinkedBlockingQueue<>();
    m_dataProcessor = new DataProcessor();
    m_dataOscSenderMap = new HashMap<>();

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

    if (m_dataOscSenderMap == null)
    {
      m_dataOscSenderMap = new HashMap<>();
    }

    if (savedInstanceState != null)
    {
      ArrayList<String> list = savedInstanceState.getStringArrayList(OSC_SENDERS);
      for (int i = 0; i < list.size(); i += 2)
      {
        String ipAddress = list.get(i);
        Integer port = Integer.valueOf(list.get(i + 1));
        DataOscSender oscSender = new DataOscSender(ipAddress, port); //todo put processor into stop/pause/kill logic stream
        m_dataOscSenderMap.put(ipAddress, oscSender);
        if (muse != null && muse.getConnectionState() == ConnectionState.CONNECTED && dataTransmission)
        {
          new Thread(oscSender).start();
          m_sendLocationsText.append(ipAddress + ":" + port + "\n");
        }
      }

      if (list.size() > 0)
      {
        m_dataProcessor.setRunning(true);
        new Thread(m_dataProcessor).start();
      }
    }
    Log.i("Muse Headband", "libmuse version=" + LibMuseVersion.SDK_VERSION);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState)
  {
    super.onSaveInstanceState(outState);
    ArrayList<String> senders = new ArrayList<>();
    for (DataOscSender sender : m_dataOscSenderMap.values())
    {
      senders.add(sender.getIpAddress());
      senders.add(sender.getPort());
    }
    outState.putStringArrayList(OSC_SENDERS, senders);
  }

  private void closeSendersAndProcessors()
  {
    for (Iterator<DataOscSender> iter = m_dataOscSenderMap.values().iterator(); iter.hasNext(); )
    {
      DataOscSender sender = iter.next();
      sender.setSenderRunning(false);
      iter.remove();
    }

    m_dataProcessor.setRunning(false);
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
      DataOscSender dataOscSender = new DataOscSender(ipAddress, port);
      m_dataOscSenderMap.put(ipAddress, dataOscSender);
      new Thread(dataOscSender).start();

      if (!m_dataProcessor.isRunning())
      {
        m_dataProcessor.setRunning(true);
        new Thread(m_dataProcessor).start();
      }

      for (DataOscSender sender : m_dataOscSenderMap.values())
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

  class DataProcessor implements Runnable
  {
    private volatile boolean m_running = false;
    private long m_getIndex = 0, m_putIndex = 0;
    private ArrayList<Object> m_oscSendList;
    private Float[] m_fp1fp2Alpha, m_fp1fp2Beta;
    //private final ConcurrentHashMap<String, BlockingQueue<Float>> m_dataQueueMap;
    private final ConcurrentHashMap<String, IDataFilter> m_dataFilterMap;
    private final ConcurrentHashMap<Long, Float[]> m_alphaFpValueMap, m_betaFpValueMap;
    private final BlockingQueue<ArrayList<Object>> m_fpArousalQueue, m_fpValenceQueue;

    public DataProcessor()
    {
      m_dataFilterMap = new ConcurrentHashMap<>();
      //m_dataFilterMap.put(MELLOW, new MeanFilter(MEAN_SAMPLE_SIZE*2));
      //m_dataFilterMap.put(CONCENTRATION, new MeanFilter(MEAN_SAMPLE_SIZE*2));
      int MAX_MIN_SIZE = 100;
      int MEAN_SIZE = 25;
      m_dataFilterMap.put(VALENCE_GIRALDO_RAMIREZ, new NormalizeMeanFilter(MAX_MIN_SIZE, MEAN_SIZE));
      m_dataFilterMap.put(AROUSAL_GIRALDO_RAMIREZ, new NormalizeMeanFilter(MAX_MIN_SIZE, MEAN_SIZE));
      m_dataFilterMap.put(VALENCE_EMIL, new NormalizeMeanFilter(MAX_MIN_SIZE, MEAN_SIZE));
      m_dataFilterMap.put(AROUSAL_EMIL, new NormalizeMeanFilter(MAX_MIN_SIZE, MEAN_SIZE));
      m_alphaFpValueMap = new ConcurrentHashMap<>();
      m_betaFpValueMap = new ConcurrentHashMap<>();
      m_fpArousalQueue = new LinkedBlockingQueue<>();
      m_fpValenceQueue = new LinkedBlockingQueue<>();
    }

    @Override
    public void run()
    {
      while (m_running)
      {
        try
        {
          computeFpArousalValence();
          //calculateAverages();
        }
        catch (IOException | InterruptedException e)
        {
          Log.e("DataProcessor", e.toString());
        }
      }
    }

    private void computeFpArousalValence() throws IOException, InterruptedException
    {
      while (m_betaFpValueMap.containsKey(m_getIndex) && m_alphaFpValueMap.containsKey(m_getIndex))
      {
        m_oscSendList = new ArrayList<>();
        m_fp1fp2Alpha = m_alphaFpValueMap.remove(m_getIndex);
        m_fp1fp2Beta = m_betaFpValueMap.remove(m_getIndex);

        // arousal
        float arousalEmil = ((m_fp1fp2Alpha[0] - m_fp1fp2Beta[0]) + (m_fp1fp2Alpha[1] - m_fp1fp2Beta[1])) / 2;
        //m_oscSendList.add(arousalEmil);
        // Sergio Giraldo, Rafael Ramirez:2013:Brain-Activity-Driven Real-Time Music Emotive Control:4
        m_dataFilterMap.get(AROUSAL_EMIL).put(arousalEmil);
        float arousal = (m_fp1fp2Beta[0] + m_fp1fp2Beta[1]) / (m_fp1fp2Alpha[0] + m_fp1fp2Alpha[1]);
        //m_oscSendList.add(arousal);
        m_dataFilterMap.get(AROUSAL_GIRALDO_RAMIREZ).put(arousal);
        m_fpArousalQueue.add(m_oscSendList);

        // valence
        // Kenneth Hugdahl, Richard J. Davidson:2003:The asymmetrical brain:568
        m_oscSendList = new ArrayList<>();
        float valenceEmil = m_fp1fp2Alpha[1] - m_fp1fp2Alpha[0];
        //m_oscSendList.add(valenceEmil);
        // Sergio Giraldo, Rafael Ramirez:2013:Brain-Activity-Driven Real-Time Music Emotive Control:4
        float valence = m_fp1fp2Alpha[1] / m_fp1fp2Beta[1] - m_fp1fp2Alpha[0] / m_fp1fp2Beta[0];
        //m_oscSendList.add(valence);
        m_dataFilterMap.get(VALENCE_GIRALDO_RAMIREZ).put(valence);
        m_dataFilterMap.get(VALENCE_EMIL).put(valenceEmil);
        m_fpValenceQueue.add(m_oscSendList);

        m_getIndex++;
      }
    }

    private void calculateAverages()
    {
      for (IDataFilter filter : m_dataFilterMap.values())
      {
        filter.process();
      }
    }

    public synchronized void setRunning(boolean running)
    {
      m_running = running;
    }

    public synchronized Float getDataFilterValue(String key)
    {
      return (Float)m_dataFilterMap.get(key).getValue();
    }

    public boolean isRunning()
    {
      return m_running;
    }

    public void putDataFilterValue(String key, float value) throws InterruptedException
    {
      IDataFilter iDataFilter = m_dataFilterMap.get(key);
      if (iDataFilter != null)
      {
        iDataFilter.put(value);
      }
    }

    public synchronized void putAlphaFp(final Float[] floats)
    {
      m_alphaFpValueMap.put(m_putIndex, floats);
      if (m_betaFpValueMap.containsKey(m_putIndex))
      {
        m_putIndex++;
      }
    }

    public synchronized void putBetaFp(final Float[] floats)
    {
      m_betaFpValueMap.put(m_putIndex, floats);
      if (m_alphaFpValueMap.containsKey(m_putIndex))
      {
        m_putIndex++;
      }
    }

    public ArrayList<Object> getFpArousal() throws InterruptedException
    {
      return m_fpArousalQueue.poll();
    }

    public ArrayList<Object> getFpValence() throws InterruptedException
    {
      return m_fpValenceQueue.poll();
    }
  }

  class DataOscSender implements Runnable
  {
    protected OSCPortOut m_oscSender;
    private volatile boolean m_senderRunning = true;
    protected String m_ipAddress, m_port;
    private ArrayList<Object> m_floats;
    private ArrayList<Object> m_sendList;

    public DataOscSender(String ipAddress, int port)
    {
      try
      {
        m_ipAddress = ipAddress;
        m_port = String.valueOf(port);
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
          MuseDataPacket p = m_dataQueue.poll();
          if (p != null)
          {
            switch (p.getPacketType())
            {
              case EEG:
                m_oscSender.send(new OSCMessage("/muse/eeg", getFloats(p)));
                break;
              case QUANTIZATION:
                m_oscSender.send(new OSCMessage("/muse/eeg/quantization", getFloats(p)));
                break;
              //case DROPPED_EEG:
              //  int droppedEeg = (int)p.getValues().get(0).doubleValue();
              //  m_oscSender.send(new OSCMessage("/muse/eeg/dropped_samples", Collections.singleton(droppedEeg)));
              //  break;
              case ACCELEROMETER:
                m_oscSender.send(new OSCMessage("/muse/acc", getFloats(p)));
                break;
              //case DROPPED_ACCELEROMETER:
              //  int droppedAcc = (int)p.getValues().get(0).doubleValue();
              //  m_oscSender.send(new OSCMessage("/muse/acc/dropped_samples", Collections.singleton(droppedAcc)));
              //  break;
              case DRL_REF:
                m_oscSender.send(new OSCMessage("/muse/drlref", getFloats(p)));
                break;
              case HORSESHOE:
                m_oscSender.send(new OSCMessage("/muse/elements/horseshoe", getFloats(p)));
                break;
              //case ARTIFACTS:
              //  m_oscSender.send(new OSCMessage("/muse/drlref", Collections.singleton(1)));
              //  break;
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
                m_sendList = m_dataProcessor.getFpArousal();
                if (m_sendList != null)
                {
                  m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_EMIL));
                  m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_GIRALDO_RAMIREZ));
                  m_oscSender.send(new OSCMessage("/muse/elements/experimental/arousal_fp", m_sendList));
                }
                m_sendList = m_dataProcessor.getFpValence();
                if (m_sendList != null)
                {
                  m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_EMIL));
                  m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_GIRALDO_RAMIREZ));
                  m_oscSender.send(new OSCMessage("/muse/elements/experimental/valence_fp", m_sendList));
                }
                break;
              case BETA_ABSOLUTE:
                m_oscSender.send(new OSCMessage("/muse/elements/beta_absolute", getFloats(p)));
                m_sendList = m_dataProcessor.getFpArousal();
                if (m_sendList != null)
                {
                  m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_EMIL));
                  m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_GIRALDO_RAMIREZ));
                  m_oscSender.send(new OSCMessage("/muse/elements/experimental/arousal_fp", m_sendList));
                }
                m_sendList = m_dataProcessor.getFpValence();
                if (m_sendList != null)
                {
                  m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_EMIL));
                  m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_GIRALDO_RAMIREZ));
                  m_oscSender.send(new OSCMessage("/muse/elements/experimental/valence_fp", m_sendList));
                }
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
                m_floats = getFloats(p);
                //m_floats.add(m_dataProcessor.getMellowAvg());
                m_oscSender.send(new OSCMessage("/muse/elements/experimental/mellow", m_floats));
                break;
              case CONCENTRATION:
                m_floats = getFloats(p);
                //m_floats.add(m_dataProcessor.getConcentrationAvg());
                m_oscSender.send(new OSCMessage("/muse/elements/experimental/concentration", m_floats));
                break;
              case BATTERY:
                ArrayList<Object> returnList = new ArrayList<>();
                returnList.add((int)(p.getValues().get(0) * 100));
                returnList.add((int)(double)p.getValues().get(1));
                returnList.add((int)(double)p.getValues().get(1));
                returnList.add((int)(double)p.getValues().get(2));
                m_oscSender.send(new OSCMessage("/muse/batt", returnList));
                break;

              default:
                break;
            }
          }
          else
          {
            Thread.sleep(1);
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
        returnList.add((float)value);
      }
      return returnList;
    }

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

  class DataLslSender implements Runnable
  {
    private volatile boolean m_senderRunning = true;
    private StreamOutlet m_outlet;
    protected String m_ipAddress, m_port;
    private ArrayList<Object> m_floats;
    private float[] sample = new float[8];

    public DataLslSender(String ipAddress, int port)
    {
      m_lsl = new LslAndroidJNI();
      StreamInfo info = new StreamInfo("BioSemi", "EEG", 8, 100, ChannelFormatT.cf_float32, "myuid324457");
      m_outlet = new StreamOutlet(info);
      m_ipAddress = ipAddress;
      m_port = String.valueOf(port);
    }

    @Override
    public void run()
    {
      while (m_senderRunning)
      {
        try
        {
          MuseDataPacket p = m_dataQueue.poll();
          if (p != null)
          {
            //switch (p.getPacketType())
            //{
            //  case EEG:
            //    m_oscSender.send(new OSCMessage("/muse/eeg", getFloats(p)));
            //    break;
            //  case QUANTIZATION:
            //    m_oscSender.send(new OSCMessage("/muse/eeg/quantization", getFloats(p)));
            //    break;
            //  //case DROPPED_EEG:
            //  //  int droppedEeg = (int)p.getValues().get(0).doubleValue();
            //  //  m_oscSender.send(new OSCMessage("/muse/eeg/dropped_samples", Collections.singleton(droppedEeg)));
            //  //  break;
            //  case ACCELEROMETER:
            //    m_oscSender.send(new OSCMessage("/muse/acc", getFloats(p)));
            //    break;
            //  //case DROPPED_ACCELEROMETER:
            //  //  int droppedAcc = (int)p.getValues().get(0).doubleValue();
            //  //  m_oscSender.send(new OSCMessage("/muse/acc/dropped_samples", Collections.singleton(droppedAcc)));
            //  //  break;
            //  case DRL_REF:
            //    m_oscSender.send(new OSCMessage("/muse/drlref", getFloats(p)));
            //    break;
            //  case HORSESHOE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/horseshoe", getFloats(p)));
            //    break;
            //  //case ARTIFACTS:
            //  //  m_oscSender.send(new OSCMessage("/muse/drlref", Collections.singleton(1)));
            //  //  break;
            //  case ALPHA_RELATIVE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/alpha_relative", getFloats(p)));
            //    break;
            //  case BETA_RELATIVE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/beta_relative", getFloats(p)));
            //    break;
            //  case DELTA_RELATIVE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/delta_relative", getFloats(p)));
            //    break;
            //  case THETA_RELATIVE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/theta_relative", getFloats(p)));
            //    break;
            //  case GAMMA_RELATIVE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/gamma_relative", getFloats(p)));
            //    break;
            //  case ALPHA_ABSOLUTE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/alpha_absolute", getFloats(p)));
            //    m_sendList = m_dataProcessor.getFpArousal();
            //    if (m_sendList != null)
            //    {
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_EMIL));
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_GIRALDO_RAMIREZ));
            //      m_oscSender.send(new OSCMessage("/muse/elements/experimental/arousal_fp", m_sendList));
            //    }
            //    m_sendList = m_dataProcessor.getFpValence();
            //    if (m_sendList != null)
            //    {
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_EMIL));
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_GIRALDO_RAMIREZ));
            //      m_oscSender.send(new OSCMessage("/muse/elements/experimental/valence_fp", m_sendList));
            //    }
            //    break;
            //  case BETA_ABSOLUTE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/beta_absolute", getFloats(p)));
            //    m_sendList = m_dataProcessor.getFpArousal();
            //    if (m_sendList != null)
            //    {
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_EMIL));
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(AROUSAL_GIRALDO_RAMIREZ));
            //      m_oscSender.send(new OSCMessage("/muse/elements/experimental/arousal_fp", m_sendList));
            //    }
            //    m_sendList = m_dataProcessor.getFpValence();
            //    if (m_sendList != null)
            //    {
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_EMIL));
            //      m_sendList.add(m_dataProcessor.getDataFilterValue(VALENCE_GIRALDO_RAMIREZ));
            //      m_oscSender.send(new OSCMessage("/muse/elements/experimental/valence_fp", m_sendList));
            //    }
            //    break;
            //  case DELTA_ABSOLUTE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/delta_absolute", getFloats(p)));
            //    break;
            //  case THETA_ABSOLUTE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/theta_absolute", getFloats(p)));
            //    break;
            //  case GAMMA_ABSOLUTE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/gamma_absolute", getFloats(p)));
            //    break;
            //  case ALPHA_SCORE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/alpha_session_score", getFloats(p)));
            //    break;
            //  case BETA_SCORE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/beta_session_score", getFloats(p)));
            //    break;
            //  case DELTA_SCORE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/delta_session_score", getFloats(p)));
            //    break;
            //  case THETA_SCORE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/theta_session_score", getFloats(p)));
            //    break;
            //  case GAMMA_SCORE:
            //    m_oscSender.send(new OSCMessage("/muse/elements/gamma_session_score", getFloats(p)));
            //    break;
            //  case MELLOW:
            //    m_floats = getFloats(p);
            //    //m_floats.add(m_dataProcessor.getMellowAvg());
            //    m_oscSender.send(new OSCMessage("/muse/elements/experimental/mellow", m_floats));
            //    break;
            //  case CONCENTRATION:
            //    m_floats = getFloats(p);
            //    //m_floats.add(m_dataProcessor.getConcentrationAvg());
            //    m_oscSender.send(new OSCMessage("/muse/elements/experimental/concentration", m_floats));
            //    break;
            //  case BATTERY:
            //    ArrayList<Object> returnList = new ArrayList<>();
            //    returnList.add((int)(p.getValues().get(0) * 100));
            //    returnList.add((int)(double)p.getValues().get(1));
            //    returnList.add((int)(double)p.getValues().get(1));
            //    returnList.add((int)(double)p.getValues().get(2));
            //    m_oscSender.send(new OSCMessage("/muse/batt", returnList));
            //    break;
            //
            //  default:
            //    break;
            //}
          }
          else
          {
            Thread.sleep(1);
          }
        }
        catch (InterruptedException e)
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
        returnList.add((float)value);
      }
      return returnList;
    }

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
   * Data listener will be registered to listen for: Accelerometer, Eeg and Relative Alpha bandpower packets. In all cases we will update UI with new values. We also will log
   * message if Artifact packets contains "blink" flag. DataListener methods will be called from execution thread. If you are implementing "serious" processing algorithms inside
   * those listeners, consider to create another thread.
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
        ArrayList<Double> values = p.getValues();
        switch (p.getPacketType())
        {
          case ALPHA_ABSOLUTE:
            m_dataProcessor.putAlphaFp(new Float[]{values.get(Eeg.FP1.ordinal()).floatValue(), values.get(Eeg.FP2.ordinal()).floatValue()});
            break;
          case BETA_ABSOLUTE:
            m_dataProcessor.putBetaFp(new Float[]{values.get(Eeg.FP1.ordinal()).floatValue(), values.get(Eeg.FP2.ordinal()).floatValue()});
            break;
          //case MELLOW:
          //  m_dataProcessor.putDataFilterValue(MELLOW, values.get(0).floatValue());
          //  break;
          //case CONCENTRATION:
          //  m_dataProcessor.putDataFilterValue(CONCENTRATION, values.get(0).floatValue());
          //  break;
          default:
            break;
        }
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
