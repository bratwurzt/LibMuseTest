package com.interaxon.libmuse.filters;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author bratwurzt
 */
public class MeanFilter implements IDataFilter
{
  private Integer m_size;
  private LinkedBlockingQueue<Float> m_dataQueue;
  private final Object m_valueLock = new Object(), m_queueLock = new Object();
  private Float m_returnValue;

  public MeanFilter(Integer size)
  {
    m_dataQueue = new LinkedBlockingQueue<>();
    m_size = size;
    m_returnValue = 0f;
  }

  @Override
  public void put(Number value) throws InterruptedException
  {
    synchronized (m_queueLock)
    {
      m_dataQueue.put((Float)value);
    }

    if (m_dataQueue.size() >= m_size)
    {
      process();
    }
  }

  @Override
  public void process()
  {
    Float poll;
    synchronized (m_queueLock)
    {
      poll = m_dataQueue.peek();
      while (m_dataQueue.size() > m_size)
      {
        poll = m_dataQueue.poll();
      }
    }

    if (poll != null)
    {
      synchronized (m_valueLock)
      {
        m_returnValue = 0f;
        for (Float data : m_dataQueue)
        {
          m_returnValue += data;
        }
        m_returnValue /= m_size;
      }
    }
  }

  public Integer getSize()
  {
    return m_size;
  }

  public void setSize(Integer size)
  {
    m_size = size;
  }

  public synchronized Float getValue()
  {
    synchronized (m_valueLock)
    {
      return m_returnValue;
    }
  }
}
