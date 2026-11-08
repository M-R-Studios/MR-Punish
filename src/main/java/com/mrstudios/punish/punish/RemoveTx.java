package com.mrstudios.punish.punish;

import com.mrstudios.punish.punish.Requests.RemoveResult;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.Database;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.PunishmentRepo.Subject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Removing punishments, inside one database transaction (DESIGN.md 7.6): all duplicates in the
 * slot at once (or one given id), each checked against the issuer's weight; one refusal refuses all.
 * Rows are never deleted: they become REMOVED with who, when and why.
 */
final class RemoveTx {

    private final PunishmentRepo punishments;
    private final ActionRepo actions;

    RemoveTx(Database database) {
        this.punishments = new PunishmentRepo(database);
        this.actions = new ActionRepo(database);
    }

    /** The hashes an IP removal covers: the request's list, or the target's single hash. */
    private static List<String> hashes(Requests.Remove request) {
        List<String> hashes = new ArrayList<>(request.ipHashes());
        if (request.target().ipHash() != null && !hashes.contains(request.target().ipHash())) {
            hashes.add(request.target().ipHash());
        }
        return hashes;
    }

    /** @param reason the stored removal reason (the typed one, or the "no reason" text) */
    RemoveResult run(Connection c, Requests.Remove request, String reason, String server, long now) throws SQLException {
        Requests.Target target = request.target();
        PunishmentType type = request.type();
        List<String> hashes = type.isIp() ? hashes(request) : List.of();
        punishments.lock(c, target.uuid(), null);
        for (String hash : hashes) {
            punishments.lock(c, target.uuid(), hash);
        }

        List<Punishment> chosen = new ArrayList<>();
        if (request.id() != null) {
            Optional<Punishment> found = punishments.byId(c, request.id());
            boolean belongs = found.isPresent() && found.get().type() == type && (type.isIp()
                    ? found.get().ipHash() != null && hashes.contains(found.get().ipHash())
                    : found.get().targetUuid().equals(target.uuid()));
            if (!belongs || !found.get().isActive(now)) {
                return new RemoveResult.NotFound(request.id());
            }
            chosen.add(found.get());
        } else {
            List<Punishment> active = new ArrayList<>();
            if (type.isIp()) {
                for (String hash : hashes) {
                    active.addAll(punishments.active(c, type, new Subject(null, hash), now));
                }
            } else {
                active.addAll(punishments.active(c, type, Subject.of(type, target.uuid(), null), now));
            }
            if (type == PunishmentType.WARN) {
                active.stream().max(Comparator.comparingLong(Punishment::id)).ifPresent(chosen::add);
            } else {
                chosen.addAll(active);
            }
        }
        if (chosen.isEmpty()) {
            return new RemoveResult.None();
        }

        Authority.Standing remover = request.actor().standing();
        Authority.Verdict verdict = Authority.checkRemoval(remover, target.uuid(),
                chosen.stream().map(Punishment::issuerWeight).toList());
        if (verdict == Authority.Verdict.SELF) {
            return new RemoveResult.Self();
        }
        if (verdict == Authority.Verdict.ISSUER_RANK) {
            Punishment heavier = chosen.stream().filter(p -> p.issuerWeight() > remover.weight()).findFirst()
                    .orElseThrow();
            return new RemoveResult.IssuerRank(heavier.id());
        }

        List<Long> ids = chosen.stream().map(Punishment::id).toList();
        int changed = punishments.markRemoved(c, ids, remover.uuid(), remover.name(), now, reason);
        if (changed != ids.size()) {
            // Another server changed one of them in between; the row locks should prevent this.
            throw new SQLException("Expected to remove " + ids.size() + " punishment(s) but " + changed + " changed");
        }
        actions.insert(c, new ActionRepo.Action(now, server, remover.uuid(), remover.name(), "REMOVE", target.uuid(),
                target.name(), ids.get(0), type + " " + ids.stream().map(i -> "#" + i).toList() + " " + reason));
        List<Punishment> removed = new ArrayList<>();
        for (long id : ids) {
            removed.add(punishments.byId(c, id).orElseThrow());
        }
        return new RemoveResult.Removed(removed);
    }
}
