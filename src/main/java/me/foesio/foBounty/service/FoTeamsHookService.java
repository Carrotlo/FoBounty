package me.foesio.foBounty.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class FoTeamsHookService {
    private final JavaPlugin plugin;
    private Class<?> cachedFoTeamsClass;
    private Method getTeamServiceMethod;
    private Method teamOfMethod;
    private Class<?> cachedTeamClass;
    private Method teamGetIdMethod;
    private boolean warnedReflectionFailure;
    private boolean hookDisabled;

    public FoTeamsHookService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean areInSameTeam(UUID firstPlayer, UUID secondPlayer) {
        if (firstPlayer == null || secondPlayer == null) {
            return false;
        }
        if (hookDisabled) {
            return false;
        }
        Plugin foTeams = Bukkit.getPluginManager().getPlugin("FoTeams");
        if (foTeams == null || !foTeams.isEnabled()) {
            return false;
        }

        try {
            ensureHookMethods(foTeams);
            Object teamService = getTeamServiceMethod.invoke(foTeams);
            if (teamService == null) {
                return false;
            }
            Integer firstTeamId = resolveTeamId(teamService, firstPlayer);
            if (firstTeamId == null) {
                return false;
            }
            Integer secondTeamId = resolveTeamId(teamService, secondPlayer);
            return secondTeamId != null && firstTeamId.equals(secondTeamId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            hookDisabled = true;
            if (!warnedReflectionFailure) {
                warnedReflectionFailure = true;
                plugin.getLogger().warning("FoTeams hook failed, disabling same-team claim block until restart: " + exception.getMessage());
            }
            return false;
        }
    }

    private synchronized void ensureHookMethods(Plugin foTeams) throws ReflectiveOperationException {
        if (cachedFoTeamsClass == foTeams.getClass() && getTeamServiceMethod != null && teamOfMethod != null) {
            return;
        }

        cachedFoTeamsClass = foTeams.getClass();
        getTeamServiceMethod = cachedFoTeamsClass.getMethod("getTeamService");
        Object teamService = getTeamServiceMethod.invoke(foTeams);
        if (teamService == null) {
            throw new NoSuchMethodException("FoTeams#getTeamService returned null");
        }
        teamOfMethod = teamService.getClass().getMethod("teamOf", UUID.class);
        cachedTeamClass = null;
        teamGetIdMethod = null;
        warnedReflectionFailure = false;
    }

    private Integer resolveTeamId(Object teamService, UUID playerUuid) throws ReflectiveOperationException {
        Object optionalResult = teamOfMethod.invoke(teamService, playerUuid);
        if (!(optionalResult instanceof Optional<?> optional) || optional.isEmpty()) {
            return null;
        }
        Object team = optional.get();
        if (team == null) {
            return null;
        }

        Method getIdMethod = resolveTeamIdMethod(team.getClass());
        Object idResult = getIdMethod.invoke(team);
        if (idResult instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private synchronized Method resolveTeamIdMethod(Class<?> teamClass) throws NoSuchMethodException {
        if (cachedTeamClass == teamClass && teamGetIdMethod != null) {
            return teamGetIdMethod;
        }
        cachedTeamClass = teamClass;
        teamGetIdMethod = teamClass.getMethod("getId");
        return teamGetIdMethod;
    }
}
