package com.interaxon.test.libmuse.helper;

/**
 * @author bratwurzt
 */
public class XORShiftRandom
{
  private long m_last;

  public XORShiftRandom()
  {
    this(System.currentTimeMillis());
  }

  public XORShiftRandom(long seed)
  {
    this.m_last = seed;
  }

  public int nextInt(int min, int max)
  {
    m_last ^= (m_last << 21);
    m_last ^= (m_last >>> 35);
    m_last ^= (m_last << 4);
    int out = (int)m_last % (max - min);
    return ((out < 0) ? -out : out) + min;
  }
}
