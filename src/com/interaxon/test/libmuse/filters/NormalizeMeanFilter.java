package com.interaxon.test.libmuse.filters;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author bratwurzt
 */
public class NormalizeMeanFilter implements IDataFilter
{
  private Integer m_maxMinSize;
  private Integer m_meanSize;
  private LinkedBlockingQueue<Float> m_dataQueue;
  private final Object m_valueLock = new Object(), m_queueLock = new Object();
  private Float m_returnValue;

  public NormalizeMeanFilter(Integer maxMinSize, Integer meanSize)
  {
    m_dataQueue = new LinkedBlockingQueue<>();
    m_maxMinSize = maxMinSize;
    m_meanSize = meanSize;
    m_returnValue = 0f;
  }

  @Override
  public void put(Number value) throws InterruptedException
  {
    synchronized (m_queueLock)
    {
      m_dataQueue.put((Float)value);
    }

    if (m_dataQueue.size() >= m_maxMinSize)
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
      while (m_dataQueue.size() > m_maxMinSize)
      {
        poll = m_dataQueue.poll();
      }
    }

    if (poll != null)
    {
      synchronized (m_valueLock)
      {
        m_returnValue = 0f;
        Float[] floats = m_dataQueue.toArray(new Float[m_dataQueue.size()]);
        Float val, max = 0f, min = Float.MAX_VALUE;
        for (int i = 0; i < floats.length; i++)
        {
          val = floats[i];
          if (val > max)
          {
            max = val;
          }
          if (val < min)
          {
            min = val;
          }
          if (i >= m_maxMinSize - m_meanSize)
          {
            m_returnValue += val;
          }
        }
        m_returnValue /= m_meanSize;
        m_returnValue = (m_returnValue - min) / (max - min);
      }
    }
  }

  public synchronized Float getValue()
  {
    synchronized (m_valueLock)
    {
      return m_returnValue;
    }
  }
}
