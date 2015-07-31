package com.interaxon.test.libmuse.filters;

/**
 * @author bratwurzt
 */
public interface IDataFilter<V extends Number>
{
  void process();

  void put(V value) throws InterruptedException;

  V getValue();
}
