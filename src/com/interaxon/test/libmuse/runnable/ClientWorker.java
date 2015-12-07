package com.interaxon.test.libmuse.runnable;

import java.io.FileOutputStream;
import java.io.OutputStream;

import android.annotation.TargetApi;
import android.os.Build;
import eu.fistar.sdcs.pa.ZephyrProtos;

/**
 * @author bratwurzt
 */
public abstract class ClientWorker implements Runnable
{
  protected ZephyrProtos.ObservationsPB m_observations;
  protected OutputStream m_outputStream;

  public ClientWorker(ZephyrProtos.ObservationsPB observations)
  {
    m_observations = observations;
  }

  abstract OutputStream getOutputStream() throws Exception;

  @TargetApi(Build.VERSION_CODES.KITKAT)
  @Override
  public void run()
  {
    try
    {
      try (OutputStream outputStream = getOutputStream())
      {
        write(outputStream);
      }
      catch (Exception e)
      {
        e.printStackTrace();
      }
      finally
      {
        getOutputStream().close();
      }
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }
  }

  protected abstract void write(OutputStream outputStream) throws Exception;

  public abstract void close() throws Exception;
}
