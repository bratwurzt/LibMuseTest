package com.interaxon.test.libmuse.runnable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import eu.fistar.sdcs.pa.ZephyrProtos;

/**
 * @author bratwurzt
 */
public class LocalClientSaveWorker extends ClientWorker
{
  private File m_observationsFile;

  public LocalClientSaveWorker(File observationsFile, ZephyrProtos.ObservationsPB observations)
  {
    super(observations);
    m_observationsFile = observationsFile;
  }

  @Override
  OutputStream getOutputStream() throws Exception
  {
    if (m_outputStream == null)
    {
      m_outputStream = new FileOutputStream(m_observationsFile, true);
    }
    return m_outputStream;
  }

  @Override
  protected void write(OutputStream outputStream) throws Exception
  {
    m_observations.writeDelimitedTo(outputStream);
  }

  @Override
  public void close() throws Exception
  {
  }
}
