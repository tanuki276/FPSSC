using System.Numerics;
using FPSSC;

static void Check(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}
static void Throws<T>(Action action) where T : Exception
{
    try { action(); }
    catch (T) { return; }
    throw new Exception($"Expected {typeof(T).Name}");
}

Throws<ArgumentOutOfRangeException>(() => new PixelFrame(0, 10));
Throws<ArgumentOutOfRangeException>(() => new PixelFrame(10, -1));
Throws<OverflowException>(() => new PixelFrame(int.MaxValue, 2));
var frame = new PixelFrame(20, 20);
Throws<ArgumentOutOfRangeException>(() => { _ = frame[20, 0]; });
Throws<ArgumentOutOfRangeException>(() => { _ = frame[-1, 1]; });
Throws<ArgumentOutOfRangeException>(() => { _ = frame[0, 20]; });
frame[4, 6] = new Rgb(50, 170, 245);
frame[16, 16] = new Rgb(100, 230, 255);
var state = new SyntheticVision().Analyze(frame, 0, 5, 0);
Check(state.Player == new Vector2(4, 6), "Friendly shots must not shift the player centroid");
var enemy = new Detection(state.Player, 9, Kind.Enemy);
var command = new ArenaPolicy().Decide(state with { Enemies = new[] { enemy } });
Check(float.IsFinite(command.Aim.X) && float.IsFinite(command.Move.Y), "Overlapping targets must produce finite controls");
using var cts = new CancellationTokenSource();
cts.Cancel();
Throws<OperationCanceledException>(() => { _ = new SyntheticArena().CaptureAsync(cts.Token); });
Console.WriteLine("FPSSC: 9 regression checks passed");
