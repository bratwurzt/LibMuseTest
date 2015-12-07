package com.interaxon.test.libmuse.runnable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Enumeration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import eu.fistar.sdcs.pa.ZephyrProtos;

/**
 * @author bratwurzt
 */
public class RemoteClientSaveWorker extends ClientWorker
{
  private KeyStore m_keystore;
  private SSLSocket m_socket;

  public RemoteClientSaveWorker(ZephyrProtos.ObservationsPB observations, KeyStore keystore)
  {
    super(observations);
    m_keystore = keystore;
  }

  @Override
  public OutputStream getOutputStream()
      throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException, IOException
  {
    if (m_socket == null || !m_socket.isConnected())
    {
      m_socket = createSslSocket(m_keystore, 8100);
    }
    return m_socket.getOutputStream();
  }

  @Override
  protected void write(OutputStream outputStream) throws Exception
  {
    m_observations.writeTo(outputStream);
  }

  @Override
  protected void close() throws IOException
  {
    m_socket.close();
  }

  protected SSLSocket createSslSocket(KeyStore keystore, int serverPort)
      throws NoSuchAlgorithmException, KeyStoreException, IOException, CertificateException, UnrecoverableKeyException, KeyManagementException
  {
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
    trustManagerFactory.init(keystore);
    TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
    KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
    kmf.init(keystore, "klient1".toCharArray());
    SSLContext sc = SSLContext.getInstance("TLS");
    sc.init(kmf.getKeyManagers(), trustManagers, new SecureRandom());
    SSLSocketFactory socketFactory = sc.getSocketFactory();
    return getSslSocket(serverPort, socketFactory);
  }

  private SSLSocket getSslSocket(int serverPort, SSLSocketFactory socketFactory) throws IOException
  {
    int localPort = 49152;
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
    return (SSLSocket)socketFactory.createSocket(InetAddress.getByName("188.230.143.188"), serverPort, localInetAddress, localPort);
  }
}
