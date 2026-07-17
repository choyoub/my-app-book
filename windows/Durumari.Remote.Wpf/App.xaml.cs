using System;
using System.Threading;
using System.Windows;

namespace Durumari.Remote.Wpf;

public partial class App : System.Windows.Application
{
    private const string SingleInstanceMutexName =
        @"Local\Durumari.Remote.6D2C8D09-38CE-4D5C-AF10-CE071F10F66A.Mutex";
    private const string ActivationEventName =
        @"Local\Durumari.Remote.6D2C8D09-38CE-4D5C-AF10-CE071F10F66A.Activate";

    private EventWaitHandle? _activationEvent;
    private Mutex? _singleInstanceMutex;
    private ManualResetEvent? _shutdownEvent;
    private Thread? _activationThread;
    private bool _ownsMutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _activationEvent = new EventWaitHandle(
            false,
            EventResetMode.AutoReset,
            ActivationEventName);
        _singleInstanceMutex = new Mutex(false, SingleInstanceMutexName);
        try
        {
            _ownsMutex = _singleInstanceMutex.WaitOne(0, false);
        }
        catch (AbandonedMutexException)
        {
            _ownsMutex = true;
        }

        if (!_ownsMutex)
        {
            _activationEvent.Set();
            Shutdown();
            return;
        }

        var mainWindow = new MainWindow();
        MainWindow = mainWindow;
        mainWindow.Show();

        _shutdownEvent = new ManualResetEvent(false);
        _activationThread = new Thread(() => ListenForActivation(mainWindow))
        {
            IsBackground = true,
            Name = "durumari-wpf-single-instance-activation",
        };
        _activationThread.Start();
    }

    private void ListenForActivation(MainWindow mainWindow)
    {
        if (_activationEvent is null || _shutdownEvent is null) return;
        var waitHandles = new WaitHandle[] { _activationEvent, _shutdownEvent };
        while (WaitHandle.WaitAny(waitHandles) == 0)
        {
            if (Dispatcher.HasShutdownStarted) return;
            _ = Dispatcher.BeginInvoke(mainWindow.OpenMainWindow);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _shutdownEvent?.Set();
        _activationThread?.Join(TimeSpan.FromSeconds(1));
        if (_ownsMutex) _singleInstanceMutex?.ReleaseMutex();
        _shutdownEvent?.Dispose();
        _activationEvent?.Dispose();
        _singleInstanceMutex?.Dispose();
        base.OnExit(e);
    }
}
