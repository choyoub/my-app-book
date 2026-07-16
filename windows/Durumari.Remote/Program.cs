namespace Durumari.Remote;

internal static class Program
{
    private const string SingleInstanceMutexName = @"Local\Durumari.Remote.6D2C8D09-38CE-4D5C-AF10-CE071F10F66A.Mutex";
    private const string ActivationEventName = @"Local\Durumari.Remote.6D2C8D09-38CE-4D5C-AF10-CE071F10F66A.Activate";

    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();

        using var activationEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ActivationEventName);
        using var singleInstanceMutex = new Mutex(false, SingleInstanceMutexName);
        var ownsMutex = false;
        try
        {
            try
            {
                ownsMutex = singleInstanceMutex.WaitOne(0, false);
            }
            catch (AbandonedMutexException)
            {
                ownsMutex = true;
            }

            if (!ownsMutex)
            {
                activationEvent.Set();
                return;
            }

            using var shutdownEvent = new ManualResetEvent(false);
            using var mainForm = new MainForm();
            _ = mainForm.Handle;
            var activationThread = new Thread(() => ListenForActivation(mainForm, activationEvent, shutdownEvent))
            {
                IsBackground = true,
                Name = "durumari-single-instance-activation",
            };
            activationThread.Start();
            try
            {
                Application.Run(mainForm);
            }
            finally
            {
                shutdownEvent.Set();
                activationThread.Join(TimeSpan.FromSeconds(1));
            }
        }
        finally
        {
            if (ownsMutex) singleInstanceMutex.ReleaseMutex();
        }
    }

    private static void ListenForActivation(
        MainForm mainForm,
        EventWaitHandle activationEvent,
        WaitHandle shutdownEvent)
    {
        var waitHandles = new WaitHandle[] { activationEvent, shutdownEvent };
        while (WaitHandle.WaitAny(waitHandles) == 0)
        {
            if (mainForm.IsDisposed) return;
            try
            {
                mainForm.BeginInvoke(new Action(mainForm.OpenMainWindow));
            }
            catch (InvalidOperationException)
            {
                return;
            }
        }
    }
}
