package com.interaxon.test.libmuse.runnable;

import java.io.OutputStream;

import eu.fistar.sdcs.pa.ZephyrProtos;

/**
 * @author bratwurzt
 */
public abstract class ClientWorker implements Runnable
{
  protected ZephyrProtos.ObservationsPB m_observations;

  public ClientWorker(ZephyrProtos.ObservationsPB observations)
  {
    m_observations = observations;
  }

  abstract OutputStream getOutputStream() throws Exception;

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
        close();
      }
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }
  }

  protected abstract void write(OutputStream outputStream) throws Exception;

  protected abstract void close() throws Exception;
}
