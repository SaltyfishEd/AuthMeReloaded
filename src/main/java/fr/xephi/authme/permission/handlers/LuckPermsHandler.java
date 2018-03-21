package fr.xephi.authme.permission.handlers;

import fr.xephi.authme.OfflinePlayerInfo;
import fr.xephi.authme.permission.PermissionNode;
import fr.xephi.authme.permission.PermissionsSystemType;
import fr.xephi.authme.util.OptionalUtils;
import me.lucko.luckperms.LuckPerms;
import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.DataMutateResult;
import me.lucko.luckperms.api.Group;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.PermissionHolder;
import me.lucko.luckperms.api.User;
import me.lucko.luckperms.api.caching.PermissionData;
import me.lucko.luckperms.api.caching.UserData;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static fr.xephi.authme.util.OptionalUtils.handleOptional;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Handler for LuckPerms.
 *
 * @see <a href="https://www.spigotmc.org/resources/luckperms-an-advanced-permissions-system.28140/">LuckPerms SpigotMC page</a>
 * @see <a href="https://github.com/lucko/LuckPerms">LuckPerms on Github</a>
 */
public class LuckPermsHandler implements PermissionHandler {

    private LuckPermsApi luckPermsApi;

    public LuckPermsHandler() throws PermissionHandlerException {
        try {
            luckPermsApi = LuckPerms.getApi();
        } catch (IllegalStateException e) {
            throw new PermissionHandlerException("Could not get api of LuckPerms", e);
        }
    }

    @Override
    public PermissionsSystemType getPermissionSystem() {
        return PermissionsSystemType.LUCK_PERMS;
    }

    @Override
    public boolean hasGroupSupport() {
        return true;
    }

    private Optional<User> getUser(Player player) {
        return luckPermsApi.getUserSafe(player.getUniqueId());
    }

    private CompletableFuture<User> getUserOffline(OfflinePlayerInfo offlineInfo) {
        if (!offlineInfo.getUniqueId().isPresent()) {
            throw new IllegalStateException("Tried to obtain an offline LuckPerms User but the" +
                "server doesn't support UUIDs!");
        }
        return luckPermsApi.getUserManager().loadUser(offlineInfo.getUniqueId().get(), offlineInfo.getName());
    }

    private Optional<Group> getGroup(String groupName) {
        return luckPermsApi.getGroupSafe(groupName);
    }

    private Node getGroupNode(Group group) {
        return luckPermsApi.getNodeFactory().makeGroupNode(group).build();
    }

    private <T> T processUser(Player player, String groupName, BiFunction<User, Group, T> action, T failed) {
        return handleOptional(getGroup(groupName),
            group -> OptionalUtils.handleOptional(getUser(player),
                user -> action.apply(user, group),
                () -> failed
            ),
            () -> failed
        );
    }

    private <T> CompletableFuture<T> processUser(OfflinePlayerInfo offlineInfo, String groupName, BiFunction<User, Group, T> action, T failed) {
        return handleOptional(getGroup(groupName),
            group -> OptionalUtils.handleOptional(getUser(player),
                user -> action.apply(user, group),
                () -> failed
            ),
            () -> failed
        );
    }

    private void saveUser(User user) {
        luckPermsApi.getUserManager().saveUser(user); // Async, handled by LuckPerms
    }

    private List<String> getGroupsOrdered(User user) {
        return user.getOwnNodes().stream()
            .filter(Node::isGroupNode)
            .map(node -> luckPermsApi.getGroupSafe(node.getGroupName()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .distinct()
            .sorted((group1, group2) -> {
                if (group1.getName().equals(user.getPrimaryGroup()) || group2.getName().equals(user.getPrimaryGroup())) {
                    return group1.getName().equals(user.getPrimaryGroup()) ? 1 : -1;
                }

                int i = Integer.compare(group2.getWeight().orElse(0), group1.getWeight().orElse(0));
                return i != 0 ? i : group1.getName().compareToIgnoreCase(group2.getName());
            })
            .map(Group::getName)
            .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<Boolean> hasPermissionOffline(OfflinePlayerInfo offlineInfo, PermissionNode node) {
        return getUserOffline(offlineInfo).thenApply(user -> {
            UserData userData = user.getCachedData();
            PermissionData permissionData = userData.getPermissionData(Contexts.allowAll());
            return permissionData.getPermissionValue(node.getNode()).asBoolean();
        });
    }

    @Override
    public boolean addToGroup(Player player, String groupName) {
        return processUser(player, groupName, ((user, group) -> {
            if (user.setPermission(getGroupNode(group)).wasFailure()) {
                return false;
            }
            saveUser(user);
            return true;
        }), false);
    }

    @Override
    public CompletableFuture<Boolean> addToGroupOffline(OfflinePlayerInfo offlineInfo, String groupName) {
        return handleOptional(getGroup(groupName),
            group -> getUserOffline(offlineInfo).thenApply(user -> {
                if (user.setPermission(getGroupNode(group)).wasFailure()) {
                    return false;
                }
                saveUser(user);
                return true;
            }),
            () -> completedFuture(false)
        );
    }

    @Override
    public boolean isInGroup(Player player, String groupName) {
        return processUser(player, groupName, (PermissionHolder::inheritsGroup), false);
    }

    @Override
    public CompletableFuture<Boolean> isInGroupOffline(OfflinePlayerInfo offlineInfo, String groupName) {
        return handleOptional(luckPermsApi.getGroupSafe(groupName),
            group -> getUserOffline(offlineInfo).thenApply(user -> user.inheritsGroup(group)),
            () -> completedFuture(false)
        );
    }

    @Override
    public boolean removeFromGroup(Player player, String groupName) {
        return processUser(player, groupName, ((user, group) -> {
            if (user.unsetPermission(getGroupNode(group)).wasFailure()) {
                return false;
            }
            saveUser(user);
            return true;
        }), false);
    }

    @Override
    public CompletableFuture<Boolean> removeFromGroupOffline(OfflinePlayerInfo offlineInfo, String groupName) {
        return handleOptional(getGroup(groupName),
            group -> getUserOffline(offlineInfo).thenApply(user -> {
                if (user.unsetPermission(getGroupNode(group)).wasFailure()) {
                    return false;
                }
                saveUser(user);
                return true;
            }),
            () -> completedFuture(false)
        );
    }

    @Override
    public boolean setGroup(Player player, String groupName) {
        return processUser(player, groupName, (user, group) -> {
            if (user.setPermission(getGroupNode(group)) == DataMutateResult.FAIL) {
                return false;
            }
            user.clearMatching(node -> node.isGroupNode() && !node.getGroupName().equals(group.getName()));
            saveUser(user);
            return true;
        }, false);
    }

    @Override
    public CompletableFuture<Boolean> setGroupOffline(OfflinePlayerInfo offlineInfo, String groupName) {
        return handleOptional(getGroup(groupName),
            group -> getUserOffline(offlineInfo).thenApply(user -> {
                if (user.setPermission(getGroupNode(group)) == DataMutateResult.FAIL) {
                    return false;
                }
                user.clearMatching(node -> node.isGroupNode() && !node.getGroupName().equals(group.getName()));
                saveUser(user);
                return true;
            }),
            () -> completedFuture(false)
        );
    }

    @Override
    public List<String> getGroups(Player player) {
        return handleOptional(getUser(player), this::getGroupsOrdered, Collections::emptyList);
    }

    @Override
    public CompletableFuture<List<String>> getGroupsOffline(OfflinePlayerInfo offlineInfo) {
        return getUserOffline(offlineInfo).thenApply(this::getGroupsOrdered);
    }

}
