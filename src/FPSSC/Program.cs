using System.Diagnostics;
using System.Numerics;

namespace FPSSC;

public readonly record struct Rgb(byte R, byte G, byte B);

public sealed class PixelFrame
{
    public int Width { get; }
    public int Height { get; }
    public Rgb[] Pixels { get; }

    public PixelFrame(int width, int height)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(width);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(height);
        Width = width;
        Height = height;
        Pixels = new Rgb[checked(width * height)];
    }

    public ref Rgb this[int x, int y]
    {
        get
        {
            if ((uint)x >= (uint)Width) throw new ArgumentOutOfRangeException(nameof(x));
            if ((uint)y >= (uint)Height) throw new ArgumentOutOfRangeException(nameof(y));
            return ref Pixels[y * Width + x];
        }
    }
    public void Clear(Rgb color) => Array.Fill(Pixels, color);
}

public readonly record struct Detection(Vector2 Position, float Radius, Kind Kind);
public enum Kind { Player, Enemy, Projectile }

public sealed record WorldState(
    Vector2 Player,
    IReadOnlyList<Detection> Enemies,
    IReadOnlyList<Detection> Projectiles,
    int Hp,
    int Score,
    long Tick);

public readonly record struct ControlCommand(Vector2 Move, Vector2 Aim, bool Fire)
{
    public static readonly ControlCommand Idle = new(Vector2.Zero, Vector2.UnitX, false);
}

public interface IFrameSource
{
    ValueTask<PixelFrame> CaptureAsync(CancellationToken cancellationToken);
}

public interface IVisionPipeline
{
    WorldState Analyze(PixelFrame frame, long tick, int hp, int score);
}

public interface IPolicy
{
    ControlCommand Decide(WorldState world);
}

public interface IInputSink
{
    void Apply(ControlCommand command, float dt);
}

public sealed class AgentLoop
{
    private readonly IFrameSource _frames;
    private readonly IVisionPipeline _vision;
    private readonly IPolicy _policy;
    private readonly IInputSink _input;

    public AgentLoop(IFrameSource frames, IVisionPipeline vision, IPolicy policy, IInputSink input)
    {
        _frames = frames;
        _vision = vision;
        _policy = policy;
        _input = input;
    }

    public async Task RunAsync(Func<(int hp, int score)> stats, CancellationToken cancellationToken)
    {
        long tick = 0;
        var loopClock = Stopwatch.StartNew();
        var last = loopClock.Elapsed;

        while (!cancellationToken.IsCancellationRequested)
        {
            var total = Stopwatch.StartNew();
            var capture = Stopwatch.StartNew();
            PixelFrame frame = await _frames.CaptureAsync(cancellationToken);
            capture.Stop();

            (int hp, int score) = stats();
            var vision = Stopwatch.StartNew();
            WorldState world = _vision.Analyze(frame, tick, hp, score);
            vision.Stop();

            var policy = Stopwatch.StartNew();
            ControlCommand command = _policy.Decide(world);
            policy.Stop();

            TimeSpan now = loopClock.Elapsed;
            float dt = Math.Clamp((float)(now - last).TotalSeconds, 0.001f, 0.05f);
            last = now;
            _input.Apply(command, dt);
            total.Stop();

            if (tick % 120 == 0)
            {
                Console.WriteLine(
                    $"tick={tick} hp={world.Hp} score={world.Score} enemies={world.Enemies.Count} projectiles={world.Projectiles.Count} " +
                    $"capture={capture.Elapsed.TotalMilliseconds:F2}ms vision={vision.Elapsed.TotalMilliseconds:F2}ms " +
                    $"policy={policy.Elapsed.TotalMilliseconds:F2}ms total={total.Elapsed.TotalMilliseconds:F2}ms");
            }

            tick++;
            await Task.Delay(8, cancellationToken);
        }
    }
}

public sealed class SyntheticVision : IVisionPipeline
{
    public WorldState Analyze(PixelFrame frame, long tick, int hp, int score)
    {
        Vector2 player = FindCentroid(frame, IsPlayer, out _) ?? new(frame.Width / 2f, frame.Height / 2f);
        var enemies = FindBlobs(frame, IsEnemy, Kind.Enemy);
        var projectiles = FindBlobs(frame, IsProjectile, Kind.Projectile);
        return new WorldState(player, enemies, projectiles, hp, score, tick);
    }

    private static List<Detection> FindBlobs(PixelFrame frame, Func<Rgb, bool> predicate, Kind kind)
    {
        var visited = new bool[frame.Pixels.Length];
        var output = new List<Detection>();
        int[] dx = [1, -1, 0, 0];
        int[] dy = [0, 0, 1, -1];
        var queue = new Queue<int>();

        for (int y = 0; y < frame.Height; y += 2)
        {
            for (int x = 0; x < frame.Width; x += 2)
            {
                int start = y * frame.Width + x;
                if (visited[start] || !predicate(frame.Pixels[start])) continue;

                visited[start] = true;
                queue.Enqueue(start);
                int count = 0;
                float sx = 0, sy = 0;

                while (queue.Count > 0)
                {
                    int p = queue.Dequeue();
                    int px = p % frame.Width;
                    int py = p / frame.Width;
                    count++;
                    sx += px;
                    sy += py;

                    for (int i = 0; i < 4; i++)
                    {
                        int nx = px + dx[i] * 2;
                        int ny = py + dy[i] * 2;
                        if ((uint)nx >= frame.Width || (uint)ny >= frame.Height) continue;
                        int np = ny * frame.Width + nx;
                        if (!visited[np] && predicate(frame.Pixels[np]))
                        {
                            visited[np] = true;
                            queue.Enqueue(np);
                        }
                    }
                }

                if (count >= 3)
                {
                    Vector2 center = new(sx / count, sy / count);
                    float radius = MathF.Sqrt(count * 4f / MathF.PI);
                    output.Add(new Detection(center, radius, kind));
                }
            }
        }

        return output;
    }

    private static Vector2? FindCentroid(PixelFrame frame, Func<Rgb, bool> predicate, out int count)
    {
        long sx = 0, sy = 0;
        count = 0;
        for (int y = 0; y < frame.Height; y += 2)
        {
            for (int x = 0; x < frame.Width; x += 2)
            {
                if (!predicate(frame[x, y])) continue;
                sx += x;
                sy += y;
                count++;
            }
        }
        return count == 0 ? null : new Vector2(sx / (float)count, sy / (float)count);
    }

    private static bool IsPlayer(Rgb c) => c.B > 180 && c.G > 120 && c.G < 210 && c.R < 120;
    private static bool IsEnemy(Rgb c) => c.R > 190 && c.G < 100 && c.B < 100;
    private static bool IsProjectile(Rgb c) => c.R > 210 && c.G > 180 && c.B < 100;
}

public sealed class ArenaPolicy : IPolicy
{
    public ControlCommand Decide(WorldState world)
    {
        if (world.Enemies.Count == 0) return ControlCommand.Idle;

        Detection target = world.Enemies
            .OrderBy(e => Vector2.DistanceSquared(e.Position, world.Player))
            .First();

        Vector2 toEnemy = target.Position - world.Player;
        float distance = MathF.Max(1f, toEnemy.Length());
        Vector2 aim = toEnemy.LengthSquared() > 1e-6f ? Vector2.Normalize(toEnemy) : Vector2.UnitX;

        Vector2 dodge = Vector2.Zero;
        foreach (Detection p in world.Projectiles)
        {
            Vector2 away = world.Player - p.Position;
            float d2 = away.LengthSquared();
            if (d2 < 90f * 90f && d2 > 1f)
                dodge += Vector2.Normalize(away) * (1f - MathF.Sqrt(d2) / 90f);
        }

        Vector2 move;
        if (dodge.LengthSquared() > 0.02f)
        {
            move = Vector2.Normalize(dodge);
        }
        else if (distance > 150f)
        {
            move = aim;
        }
        else if (distance < 95f)
        {
            move = -aim;
        }
        else
        {
            move = new Vector2(-aim.Y, aim.X) * 0.45f;
        }

        bool fire = distance < 230f;
        return new ControlCommand(move, aim, fire);
    }
}

public sealed class SyntheticArena : IFrameSource, IInputSink
{
    private readonly PixelFrame _frame = new(480, 270);
    private readonly Random _random = new(276);
    private readonly List<Enemy> _enemies = [];
    private readonly List<Projectile> _projectiles = [];
    private Vector2 _player = new(240, 135);
    private Vector2 _move;
    private Vector2 _aim = Vector2.UnitX;
    private bool _fire;
    private long _lastShotMs;
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    public int Hp { get; private set; } = 5;
    public int Score { get; private set; }

    public SyntheticArena()
    {
        for (int i = 0; i < 5; i++) SpawnEnemy();
    }

    public ValueTask<PixelFrame> CaptureAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        Step(1f / 120f);
        Render();
        return ValueTask.FromResult(_frame);
    }

    public void Apply(ControlCommand command, float dt)
    {
        _move = command.Move;
        _aim = command.Aim.LengthSquared() > 0.01f ? Vector2.Normalize(command.Aim) : _aim;
        _fire = command.Fire;
    }

    private void Step(float dt)
    {
        _player += ClampLength(_move, 1f) * 135f * dt;
        _player.X = Math.Clamp(_player.X, 12, _frame.Width - 12);
        _player.Y = Math.Clamp(_player.Y, 12, _frame.Height - 12);

        if (_fire && _clock.ElapsedMilliseconds - _lastShotMs > 160)
        {
            _lastShotMs = _clock.ElapsedMilliseconds;
            _projectiles.Add(new Projectile(_player, _aim * 300f, true));
        }

        foreach (Enemy enemy in _enemies)
        {
            Vector2 toPlayer = _player - enemy.Position;
            if (toPlayer.LengthSquared() > 25f)
                enemy.Position += Vector2.Normalize(toPlayer) * 45f * dt;

            if (_random.NextDouble() < 0.015 && toPlayer.LengthSquared() > 1f)
                _projectiles.Add(new Projectile(enemy.Position, Vector2.Normalize(toPlayer) * 190f, false));
        }

        for (int i = _projectiles.Count - 1; i >= 0; i--)
        {
            Projectile p = _projectiles[i];
            p.Position += p.Velocity * dt;

            bool remove = p.Position.X < -10 || p.Position.Y < -10 ||
                          p.Position.X > _frame.Width + 10 || p.Position.Y > _frame.Height + 10;

            if (!remove && !p.Friendly && Vector2.DistanceSquared(p.Position, _player) < 11f * 11f)
            {
                Hp = Math.Max(0, Hp - 1);
                remove = true;
                if (Hp == 0)
                {
                    Hp = 5;
                    _player = new(240, 135);
                }
            }

            if (!remove && p.Friendly)
            {
                for (int e = _enemies.Count - 1; e >= 0; e--)
                {
                    if (Vector2.DistanceSquared(p.Position, _enemies[e].Position) < 13f * 13f)
                    {
                        _enemies.RemoveAt(e);
                        Score++;
                        SpawnEnemy();
                        remove = true;
                        break;
                    }
                }
            }

            if (remove) _projectiles.RemoveAt(i);
        }
    }

    private void Render()
    {
        _frame.Clear(new Rgb(24, 26, 31));
        DrawCircle(_player, 8, new Rgb(50, 170, 245));
        foreach (Enemy e in _enemies) DrawCircle(e.Position, 9, new Rgb(235, 60, 55));
        foreach (Projectile p in _projectiles)
            DrawCircle(p.Position, 3, p.Friendly ? new Rgb(100, 230, 255) : new Rgb(245, 205, 45));
    }

    private void DrawCircle(Vector2 c, int r, Rgb color)
    {
        int x0 = Math.Max(0, (int)c.X - r);
        int x1 = Math.Min(_frame.Width - 1, (int)c.X + r);
        int y0 = Math.Max(0, (int)c.Y - r);
        int y1 = Math.Min(_frame.Height - 1, (int)c.Y + r);
        int rr = r * r;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                if ((x - c.X) * (x - c.X) + (y - c.Y) * (y - c.Y) <= rr)
                    _frame[x, y] = color;
    }

    private void SpawnEnemy()
    {
        float x = _random.Next(30, _frame.Width - 30);
        float y = _random.Next(30, _frame.Height - 30);
        if (Vector2.DistanceSquared(new Vector2(x, y), _player) < 100f * 100f)
            x = x < _frame.Width / 2f ? _frame.Width - 40 : 40;
        _enemies.Add(new Enemy(new Vector2(x, y)));
    }

    private static Vector2 ClampLength(Vector2 value, float max)
    {
        float len2 = value.LengthSquared();
        return len2 > max * max ? Vector2.Normalize(value) * max : value;
    }

    private sealed class Enemy(Vector2 position)
    {
        public Vector2 Position = position;
    }

    private sealed class Projectile(Vector2 position, Vector2 velocity, bool friendly)
    {
        public Vector2 Position = position;
        public readonly Vector2 Velocity = velocity;
        public readonly bool Friendly = friendly;
    }
}

public static class Program
{
    public static async Task Main()
    {
        Console.WriteLine("FPSSC synthetic arena demo");
        Console.WriteLine("Press Ctrl+C to stop.\n");

        var arena = new SyntheticArena();
        var loop = new AgentLoop(arena, new SyntheticVision(), new ArenaPolicy(), arena);
        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        try
        {
            await loop.RunAsync(() => (arena.Hp, arena.Score), cts.Token);
        }
        catch (OperationCanceledException)
        {
        }
    }
}
