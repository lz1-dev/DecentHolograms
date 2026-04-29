package eu.decentsoftware.holograms.api.utils.scheduler;

import eu.decentsoftware.holograms.api.DecentHolograms;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import eu.decentsoftware.holograms.api.utils.Log;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@UtilityClass
public class S {

    private static final DecentHolograms DECENT_HOLOGRAMS = DecentHologramsAPI.get();
    private static final Plugin PLUGIN = DECENT_HOLOGRAMS.getPlugin();
    private static final boolean FOLIA = isFoliaEnvironment();
    private static final Object GLOBAL_REGION_SCHEDULER = getServerScheduler("getGlobalRegionScheduler");
    private static final Object REGION_SCHEDULER = getServerScheduler("getRegionScheduler");
    private static final Object ASYNC_SCHEDULER = getServerScheduler("getAsyncScheduler");

    public static boolean isFolia() {
        return FOLIA;
    }

    public static void stopTask(TaskHandle taskHandle) {
        if (taskHandle != null) {
            taskHandle.cancel();
        }
    }

    public static void sync(Runnable runnable) {
        if (FOLIA) {
            invokeRunnable(GLOBAL_REGION_SCHEDULER, "execute", PLUGIN, runnable);
            return;
        }
        Bukkit.getScheduler().runTask(PLUGIN, runnable);
    }

    public static TaskHandle sync(Runnable runnable, long delay) {
        if (FOLIA) {
            return invokeConsumerTask(GLOBAL_REGION_SCHEDULER, "runDelayed", PLUGIN, runnable, normalizeFoliaTickDelay(delay));
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(PLUGIN, runnable, delay));
    }

    public static TaskHandle syncTask(Runnable runnable, long interval) {
        if (FOLIA) {
            return invokeConsumerTask(GLOBAL_REGION_SCHEDULER, "runAtFixedRate", PLUGIN, runnable, 1L, normalizeFoliaTickPeriod(interval));
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(PLUGIN, runnable, 0L, interval));
    }

    public static void async(Runnable runnable) {
        if (FOLIA) {
            invokeConsumerTask(ASYNC_SCHEDULER, "runNow", PLUGIN, runnable);
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(PLUGIN, runnable);
        } catch (IllegalPluginAccessException e) {
            CompletableFuture.runAsync(runnable);
        }
    }

    public static void async(Runnable runnable, long delay) {
        if (FOLIA) {
            invokeConsumerTask(ASYNC_SCHEDULER, "runDelayed", PLUGIN, runnable, ticksToMillis(delay), TimeUnit.MILLISECONDS);
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLaterAsynchronously(PLUGIN, runnable, delay);
        } catch (IllegalPluginAccessException e) {
            CompletableFuture.runAsync(runnable);
        }
    }

    public static TaskHandle asyncTask(Runnable runnable, long interval) {
        return asyncTask(runnable, interval, 0L);
    }

    public static TaskHandle asyncTask(Runnable runnable, long interval, long delay) {
        if (FOLIA) {
            return invokeConsumerTask(ASYNC_SCHEDULER, "runAtFixedRate", PLUGIN, runnable, ticksToMillis(delay), ticksToMillis(interval), TimeUnit.MILLISECONDS);
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(PLUGIN, runnable, delay, interval));
    }

    public static void entity(Player player, Runnable runnable) {
        if (FOLIA) {
            executeEntityTask(player, runnable, 0L);
            return;
        }
        sync(runnable);
    }

    public static void entity(Player player, Runnable runnable, long delay) {
        if (FOLIA) {
            executeEntityTask(player, runnable, delay);
            return;
        }
        Bukkit.getScheduler().runTaskLater(PLUGIN, runnable, delay);
    }

    public static void region(Location location, Runnable runnable) {
        if (FOLIA) {
            invokeConsumerTask(REGION_SCHEDULER, "run", PLUGIN, location, runnable);
            return;
        }
        sync(runnable);
    }

    public static TaskHandle region(Location location, Runnable runnable, long delay) {
        if (FOLIA) {
            return invokeConsumerTask(REGION_SCHEDULER, "runDelayed", PLUGIN, location, runnable, normalizeFoliaTickDelay(delay));
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(PLUGIN, runnable, delay));
    }

    public static void teleport(Player player, Location location) {
        entity(player, () -> {
            try {
                Method teleportAsync = player.getClass().getMethod("teleportAsync", Location.class);
                teleportAsync.invoke(player, location);
            } catch (NoSuchMethodException ignored) {
                player.teleport(location);
            } catch (Exception e) {
                Log.warn("Failed to teleport player %s using async teleport, falling back to synchronous teleport.", e, player.getName());
                player.teleport(location);
            }
        });
    }

    private static boolean isFoliaEnvironment() {
        try {
            Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static Object getServerScheduler(String methodName) {
        if (!FOLIA) {
            return null;
        }
        try {
            return Bukkit.getServer().getClass().getMethod(methodName).invoke(Bukkit.getServer());
        } catch (Exception e) {
            throw new FoliaDetectionException("Failed to access Folia scheduler " + methodName + ".", e);
        }
    }

    private static void executeEntityTask(Entity entity, Runnable runnable, long delay) {
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            Method execute = findMethod(scheduler.getClass(), "execute", 4);
            Runnable retired = () -> {
            };
            execute.invoke(scheduler, PLUGIN, runnable, retired, delay);
        } catch (Exception e) {
            throw new FoliaDetectionException("Failed to execute entity scheduler task.", e);
        }
    }

    private static TaskHandle invokeConsumerTask(Object target, String methodName, Object... args) {
        try {
            Method method = findMethod(target.getClass(), methodName, args.length);
            Object[] actualArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object argument = args[i];
                if (argument instanceof Runnable) {
                    actualArgs[i] = (Consumer<Object>) scheduledTask -> ((Runnable) argument).run();
                } else {
                    actualArgs[i] = argument;
                }
            }
            Object task = method.invoke(target, actualArgs);
            return new ReflectiveTaskHandle(task);
        } catch (Exception e) {
            throw new FoliaDetectionException("Failed to invoke Folia scheduler method " + methodName + ".", e);
        }
    }

    private static void invokeRunnable(Object target, String methodName, Object... args) {
        try {
            findMethod(target.getClass(), methodName, args.length).invoke(target, args);
        } catch (Exception e) {
            throw new FoliaDetectionException("Failed to invoke Folia scheduler method " + methodName + ".", e);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameterCount);
    }

    private static long ticksToMillis(long ticks) {
        return Math.max(0L, ticks) * 50L;
    }

    private static long normalizeFoliaTickDelay(long delay) {
        return Math.max(1L, delay);
    }

    private static long normalizeFoliaTickPeriod(long period) {
        return Math.max(1L, period);
    }

    private static final class BukkitTaskHandle implements TaskHandle {

        private final BukkitTask task;

        private BukkitTaskHandle(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            task.cancel();
        }
    }

    private static final class ReflectiveTaskHandle implements TaskHandle {

        private final Object task;

        private ReflectiveTaskHandle(Object task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            if (task == null) {
                return;
            }
            try {
                Method method = task.getClass().getMethod("cancel");
                method.setAccessible(true);
                method.invoke(task);
            } catch (Exception e) {
                throw new FoliaDetectionException("Failed to cancel Folia scheduled task.", e);
            }
        }
    }

}
