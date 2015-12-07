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
  private FileOutputStream m_fileOutputStream;
  private File m_observationsFile;

  public LocalClientSaveWorker(File observationsFile, ZephyrProtos.ObservationsPB observations)
  {
    super(observations);
    m_observationsFile = observationsFile;
  }

  @Override
  OutputStream getOutputStream() throws Exception
  {
    m_fileOutputStream = new FileOutputStream(m_observationsFile, true);
    return m_fileOutputStream;
  }

  @Override
  protected void write(OutputStream outputStream) throws Exception
  {
    m_observations.writeDelimitedTo(outputStream);
  }

  @Override
  protected void close() throws Exception
  {
    m_fileOutputStream.close();
  }
}
