package com.aetherport.aether.advancement;

import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Resolve the available loadAdvancement signature instead of linking one build. */
public final class AdvancementLoader {
 private static final Map<Class<?>,Method> methods=new ConcurrentHashMap<>();
 public static Advancement load(Object unsafe,NamespacedKey key,String json)throws Throwable {
  Method method=methods.get(unsafe.getClass());
  if(method==null){
   method=Arrays.stream(unsafe.getClass().getMethods()).filter(m->m.getName().equals("loadAdvancement"))
    .filter(m->(m.getParameterCount()==2 || m.getParameterCount()==3 && m.getParameterTypes()[2]==boolean.class) && m.getParameterTypes()[0].isInstance(key) && m.getParameterTypes()[1]==String.class)
    .max(Comparator.comparingInt(Method::getParameterCount)).orElseThrow(()->new NoSuchMethodException("Paper loadAdvancement API"));
   method.setAccessible(true);methods.put(unsafe.getClass(),method);
  }
  try{return (Advancement)method.invoke(unsafe,method.getParameterCount()==3?new Object[]{key,json,true}:new Object[]{key,json});}
  catch(InvocationTargetException e){throw e.getCause();}
 }
}
