package com.interaxon.test.libmuse.runnable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Enumeration;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;

import com.interaxon.test.libmuse.helper.XORShiftRandom;
import eu.fistar.sdcs.pa.ZephyrProtos;

/**
 * @author bratwurzt
 */
public class RemoteClientSaveWorker extends ClientWorker
{
  private Socket m_socket;
  private String m_ipAddress;
  private Integer m_port;

  public RemoteClientSaveWorker(ZephyrProtos.ObservationsPB observations, Socket socket, String ipAddress, Integer port)
  {
    super(observations);
    m_socket = socket;
    m_ipAddress = ipAddress;
    m_port = port;
  }

  @Override
  public OutputStream getOutputStream()
      throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException, IOException
  {
    if (m_outputStream == null)
    {
      checkSocket();
      m_outputStream = m_socket.getOutputStream();
    }
    return m_outputStream;
  }

  @Override
  protected void write(OutputStream outputStream) throws Exception
  {
    m_observations.writeTo(outputStream);
    outputStream.flush();
  }

  @Override
  public void close() throws IOException
  {
  }

  private void checkSocket()
  {
    if (m_socket == null || !m_socket.isConnected() || m_socket.isClosed())
    {
      int i = 0;
      //while (i++ < 5)
      //{
        try
        {
          m_socket = createSocket(m_port, m_ipAddress);
          //break;
        }
        catch (IOException e)
        {
          //try
          //{
          //  Thread.sleep(10);
          //}
          //catch (InterruptedException e1)
          //{
            e.printStackTrace();
          //}
        }
      //}
    }
  }

  private Socket createSocket(int serverPort, String serverIPAddress) throws IOException
  {
    XORShiftRandom xorShiftRandom = new XORShiftRandom();
    int localPort = xorShiftRandom.nextInt(49152, 65535);
    Enumeration e = NetworkInterface.getNetworkInterfaces();
    InetAddress localInetAddress = InetAddress.getByName("localhost");
    outerLoop:
    while (e.hasMoreElements())
    {
      NetworkInterface n = (NetworkInterface)e.nextElement();
      Enumeration ee = n.getInetAddresses();
      while (ee.hasMoreElements())
      {
        InetAddress i = (InetAddress)ee.nextElement();

        if (i.isSiteLocalAddress())
        {
          localInetAddress = i;
          break outerLoop;
        }
      }
    }
    return SocketFactory.getDefault().createSocket(InetAddress.getByName(serverIPAddress), serverPort, localInetAddress, localPort);
  }
}
