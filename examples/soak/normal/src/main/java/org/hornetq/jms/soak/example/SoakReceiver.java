/*
 * Copyright 2005-2014 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.jms.soak.example;

import java.lang.Override;
import java.lang.Runnable;
import java.util.Hashtable;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.naming.InitialContext;
import javax.naming.NamingException;

public class SoakReceiver
{
   private static final Logger log = Logger.getLogger(SoakReceiver.class.getName());

   private static final String EOF = UUID.randomUUID().toString();

   public static void main(final String[] args)
   {
      Runnable runnable = new Runnable()
      {
         @Override
         public void run()
         {

            String jndiURL = System.getProperty("jndi.address");
            if(jndiURL == null)
            {
               jndiURL = args.length > 0 ? args[0] : "jnp://localhost:1099";
            }

            System.out.println("Connecting to JNDI at " + jndiURL);

            try
            {
               String fileName = SoakBase.getPerfFileName();

               SoakParams params = SoakBase.getParams(fileName);

               Hashtable<String, String> jndiProps = new Hashtable<String, String>();
               jndiProps.put("java.naming.provider.url", jndiURL);
               jndiProps.put("java.naming.factory.initial", "org.jnp.interfaces.NamingContextFactory");
               jndiProps.put("java.naming.factory.url.pkgs", "org.jboss.naming:org.jnp.interfaces");

               final SoakReceiver receiver = new SoakReceiver(jndiProps, params);

               Runtime.getRuntime().addShutdownHook(new Thread()
               {
                  @Override
                  public void run()
                  {
                     receiver.disconnect();
                  }
               });

               receiver.run();
            }
            catch (Exception e)
            {
               e.printStackTrace();
            }
         }
      };

      Thread t = new Thread(runnable);
      t.start();
   }

   private final Hashtable<String, String> jndiProps;

   private final SoakParams perfParams;

   private final ExceptionListener exceptionListener = new ExceptionListener()
   {
      public void onException(final JMSException e)
      {
         disconnect();
         connect();
      }
   };

   private final MessageListener listener = new MessageListener()
   {
      int modulo = 10000;

      private final AtomicLong count = new AtomicLong(0);

      private final long start = System.currentTimeMillis();

      long moduloStart = start;

      public void onMessage(final Message msg)
      {
         long totalDuration = System.currentTimeMillis() - start;

         try
         {
            if (SoakReceiver.EOF.equals(msg.getStringProperty("eof")))
            {
               SoakReceiver.log.info(String.format("Received %s messages in %.2f minutes", count, 1.0 * totalDuration /
                                                                                                  SoakBase.TO_MILLIS));
               SoakReceiver.log.info("END OF RUN");

               return;
            }
         }
         catch (JMSException e1)
         {
            e1.printStackTrace();
         }
         if (count.incrementAndGet() % modulo == 0)
         {
            double duration = (1.0 * System.currentTimeMillis() - moduloStart) / 1000;
            moduloStart = System.currentTimeMillis();
            SoakReceiver.log.info(String.format("received %s messages in %2.2fs (total: %.0fs)",
                                                modulo,
                                                duration,
                                                totalDuration / 1000.0));
         }
      }
   };

   private Session session;

   private Connection connection;

   private SoakReceiver(final Hashtable<String, String> jndiProps, final SoakParams perfParams)
   {
      this.jndiProps = jndiProps;
      this.perfParams = perfParams;
   }

   public void run() throws Exception
   {
      connect();

      boolean runInfinitely = perfParams.getDurationInMinutes() == -1;

      if (!runInfinitely)
      {
         Thread.sleep(perfParams.getDurationInMinutes() * SoakBase.TO_MILLIS);

         // send EOF message
         Message eof = session.createMessage();
         eof.setStringProperty("eof", SoakReceiver.EOF);
         listener.onMessage(eof);

         if (connection != null)
         {
            connection.close();
            connection = null;
         }
      }
      else
      {
         while (true)
         {
            Thread.sleep(500);
         }
      }
   }

   private void disconnect()
   {
      if (connection != null)
      {
         try
         {
            connection.setExceptionListener(null);
            connection.close();
         }
         catch (JMSException e)
         {
            e.printStackTrace();
         }
         finally
         {
            connection = null;
         }
      }
   }

   private void connect()
   {
      InitialContext ic = null;
      try
      {
         ic = new InitialContext(jndiProps);

         ConnectionFactory factory = (ConnectionFactory)ic.lookup(perfParams.getConnectionFactoryLookup());

         Destination destination = (Destination)ic.lookup(perfParams.getDestinationLookup());

         connection = factory.createConnection();
         connection.setExceptionListener(exceptionListener);

         session = connection.createSession(perfParams.isSessionTransacted(),
                                            perfParams.isDupsOK() ? Session.DUPS_OK_ACKNOWLEDGE
                                                                 : Session.AUTO_ACKNOWLEDGE);

         MessageConsumer messageConsumer = session.createConsumer(destination);
         messageConsumer.setMessageListener(listener);

         connection.start();
      }
      catch (Exception e)
      {
         e.printStackTrace();
      }
      finally
      {
         try
         {
            ic.close();
         }
         catch (NamingException e)
         {
            e.printStackTrace();
         }
      }
   }
}
