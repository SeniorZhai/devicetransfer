package org.signal.devicetransfer;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.greenrobot.eventbus.EventBus;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class DeviceTransferClient implements Handler.Callback {

  private static final String TAG = Log.tag(DeviceTransferClient.class);

  private static final int START_CLIENT               = 0;
  private static final int STOP_CLIENT                = 1;
  private static final int START_NETWORK_CLIENT       = 2;
  private static final int NETWORK_DISCONNECTED       = 3;
  private static final int CONNECT_TO_SERVICE         = 4;
  private static final int RESTART_CLIENT             = 5;
  private static final int START_IP_EXCHANGE          = 6;
  private static final int IP_EXCHANGE_SUCCESS        = 7;
  private static final int SET_VERIFIED               = 8;
  private static final int NETWORK_CONNECTION_CHANGED = 9;

  private final Context                     context;
  private       int                         remotePort;
  private       HandlerThread               commandAndControlThread;
  private final Handler                     handler;
  private final ClientTask                  clientTask;
  private final ShutdownCallback            shutdownCallback;
  private       NetworkClientThread         clientThread;
  private final Runnable                    autoRestart;
  private       IpExchange.IpExchangeThread ipExchangeThread;
  private String ipAddress;

  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  private static void update(@NonNull TransferStatus transferStatus) {
    Log.d(TAG, "transferStatus: " + transferStatus.getTransferMode().name());
    EventBus.getDefault().postSticky(transferStatus);
  }

  @AnyThread
  public DeviceTransferClient(@NonNull Context context,
                              @NonNull ClientTask clientTask,
                              @NonNull String ipAddress,
                              int ipPort,
                              @Nullable ShutdownCallback shutdownCallback)
  {
    this.context                 = context;
    this.clientTask              = clientTask;
    this.ipAddress = ipAddress;
    this.remotePort = ipPort;
    this.shutdownCallback        = shutdownCallback;
    this.commandAndControlThread = SignalExecutors.getAndStartHandlerThread("client-cnc");
    this.handler                 = new Handler(commandAndControlThread.getLooper(), this);
    this.autoRestart             = () -> {
      Log.i(TAG, "Restarting WiFi Direct since we haven't found anything yet and it could be us.");
      handler.sendEmptyMessage(RESTART_CLIENT);
    };
  }

  @MainThread
  public void start() {
    if (started.compareAndSet(false, true)) {
      update(TransferStatus.ready());
      handler.sendEmptyMessage(START_CLIENT);
    }
  }

  @MainThread
  public void stop() {
    if (stopped.compareAndSet(false, true)) {
      handler.sendEmptyMessage(STOP_CLIENT);
    }
  }

  @MainThread
  public void setVerified(boolean isVerified) {
    if (!stopped.get()) {
      handler.sendMessage(handler.obtainMessage(SET_VERIFIED, isVerified));
    }
  }

  private void shutdown() {
    stopIpExchange();
    stopNetworkClient();

    if (commandAndControlThread != null) {
      Log.i(TAG, "Shutting down command and control");
      commandAndControlThread.quit();
      commandAndControlThread.interrupt();
      commandAndControlThread = null;
    }

    EventBus.getDefault().removeStickyEvent(TransferStatus.class);
  }

  private void internalShutdown() {
    shutdown();
    if (shutdownCallback != null) {
      shutdownCallback.shutdown();
    }
  }

  @Override
  public boolean handleMessage(@NonNull Message message) {
    Log.d(TAG, "Handle message: " + message.what);
    switch (message.what) {
      case START_CLIENT:
        handler.sendMessage(handler.obtainMessage(START_NETWORK_CLIENT, ipAddress));
        break;
      case STOP_CLIENT:
        shutdown();
        break;
      case START_NETWORK_CLIENT:
        startNetworkClient((String) message.obj);
        break;
      case NETWORK_DISCONNECTED:
      case RESTART_CLIENT:
        stopNetworkClient();
        break;
      case CONNECT_TO_SERVICE:
        connectToService((String) message.obj, message.arg1);
        break;
      case START_IP_EXCHANGE:
        startIpExchange((String) message.obj);
        break;
      case IP_EXCHANGE_SUCCESS:
        ipExchangeSuccessful((String) message.obj);
        break;
      case SET_VERIFIED:
        if (clientThread != null) {
          clientThread.setVerified((Boolean) message.obj);
        }
        break;
      case NETWORK_CONNECTION_CHANGED:
        requestNetworkInfo((Boolean) message.obj);
        break;
      case NetworkClientThread.NETWORK_CLIENT_SSL_ESTABLISHED:
        update(TransferStatus.verificationRequired((Integer) message.obj));
        break;
      case NetworkClientThread.NETWORK_CLIENT_CONNECTED:
        update(TransferStatus.serviceConnected());
        break;
      case NetworkClientThread.NETWORK_CLIENT_DISCONNECTED:
        update(TransferStatus.networkConnected());
        break;
      case NetworkClientThread.NETWORK_CLIENT_STOPPED:
        update(TransferStatus.shutdown());
        internalShutdown();
        break;
      default:
        internalShutdown();
        throw new AssertionError("Unknown message: " + message.what);
    }
    return false;
  }

  private void startNetworkClient(@NonNull String serverHostAddress) {
    if (clientThread != null) {
      Log.i(TAG, "Client already running");
      return;
    }

    Log.i(TAG, "Connection established, spinning up network client.");
    clientThread = new NetworkClientThread(context,
                                           clientTask,
                                           serverHostAddress,
                                           remotePort,
                                           handler);
    clientThread.start();
  }

  private void stopNetworkClient() {
    if (clientThread != null) {
      Log.i(TAG, "Shutting down ClientThread");
      clientThread.shutdown();
      try {
        clientThread.join(TimeUnit.SECONDS.toMillis(1));
      } catch (InterruptedException e) {
        Log.i(TAG, "Client thread took too long to shutdown", e);
      }
      clientThread = null;
    }
  }

  private void connectToService(@NonNull String deviceAddress, int port) {
    if (clientThread != null) {
      Log.i(TAG, "Client is running we shouldn't be connecting again");
      return;
    }

    handler.removeCallbacks(autoRestart);

    int tries = 5;
    while ((tries--) > 0) {
      try {
        handler.obtainMessage(START_NETWORK_CLIENT, deviceAddress);
        update(TransferStatus.networkConnected());
        remotePort = port;
        return;
      } catch (Exception e) {
        Log.w(TAG, "Unable to connect, tries: " + tries);
        try {
          Thread.sleep(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException ignored) {
          Log.i(TAG, "Interrupted while connecting to service, bail now!");
          return;
        }
      }
    }

    handler.sendMessage(handler.obtainMessage(RESTART_CLIENT));
  }

  private void requestNetworkInfo(boolean isNetworkConnected) {
    if (isNetworkConnected) {
      Log.i(TAG, "Network connected, requesting network info");
      try {
//        wifiDirect.requestNetworkInfo();
      } catch (Exception e) {
        Log.e(TAG, e);
        internalShutdown();
        update(TransferStatus.failed());
      }
    } else {
      Log.i(TAG, "Network disconnected");
      handler.sendEmptyMessage(NETWORK_DISCONNECTED);
    }
  }

  private void startIpExchange(@NonNull String groupOwnerHostAddress) {
    ipExchangeThread = IpExchange.getIp(groupOwnerHostAddress, remotePort, handler, IP_EXCHANGE_SUCCESS);
  }

  private void stopIpExchange() {
    if (ipExchangeThread != null) {
      ipExchangeThread.shutdown();
      try {
        ipExchangeThread.join(TimeUnit.SECONDS.toMillis(1));
      } catch (InterruptedException e) {
        Log.i(TAG, "IP Exchange thread took too long to shutdown", e);
      }
      ipExchangeThread = null;
    }
  }

  private void ipExchangeSuccessful(@NonNull String host) {
    stopIpExchange();
    handler.sendMessage(handler.obtainMessage(START_NETWORK_CLIENT, host));
  }

}
