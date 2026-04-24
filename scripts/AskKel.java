///usr/bin/env jbang "$0" "$@" ; exit $?
// Ask a Veridian wallet to extend its KEL with an interact event.
// Reuses the same SignifyClient as src/programmable-tokens-offchain-java.
//
// Usage:
//   jbang scripts/AskKel.java
// Then paste the partner's OOBI URL when prompted.
//
// Defaults match application.yaml; override via env:
//   KERI_URL, KERI_BOOT_URL, KERI_BRAN, KERI_IDENTIFIER_NAME

//JAVA 21
//REPOS mavencentral,sonatype-snapshots=https://central.sonatype.com/repository/maven-snapshots/
//DEPS org.cardanofoundation:signify:0.1.2-PR62-d6aea58
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.slf4j:slf4j-simple:2.0.13

import com.fasterxml.jackson.core.type.TypeReference;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.cesr.Saider;
import org.cardanofoundation.signify.cesr.Salter;
import org.cardanofoundation.signify.cesr.util.Utils;
import org.cardanofoundation.signify.core.States;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

public class AskKel {

    static final String KERIA_URL = env("KERI_URL", "http://localhost:3901");
    static final String BOOT_URL = env("KERI_BOOT_URL", "http://localhost:3903");
    static final String BRAN = env("KERI_BRAN", "0ADF2TpptgqcDE5IQUF1H");
    static final String NAME = env("KERI_IDENTIFIER_NAME", "askkel");

    public static void main(String[] args) throws Exception {
        var sc = new Scanner(System.in);
        System.out.print("Veridian wallet OOBI URL: ");
        var oobi = "http://keria:3902/oobi/EKroV2s77W8TH3rAWL5sStAouyo7IX-ZGxLM44xCmShc/agent/EAAWBJCexCasxLOgbFUlCiVxgRxQv3CFWF47UznkqfUT?name=Thomas";

        System.out.println("[*] Connecting to KERIA " + KERIA_URL);
        var client = new SignifyClient(KERIA_URL, BRAN, Salter.Tier.low, BOOT_URL, null);
        try { client.connect(); } catch (Exception e) { client.boot(); client.connect(); }

        var hab = ensureIdentifier(client);
        System.out.println("[*] Sender AID: " + hab.getPrefix());

        // Print OUR OOBI so the user can register us as a contact in Veridian first.
        // Without that, KERIA drops our exn message silently and the wallet never sees a notification.
        var ourOobi = fetchOurOobi(client);
        System.out.println();
        System.out.println("================ MUTUAL OOBI REQUIRED ================");
        System.out.println("Sender OOBI (paste into Veridian → 'Add Connection'):");
        System.out.println("    " + ourOobi);
        System.out.println();
        // Quick reachability check from this process — if even WE can't reach it,
        // Veridian's KERIA inside Docker definitely can't either.
        try {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var probe = http.send(
                    HttpRequest.newBuilder(URI.create(ourOobi)).GET().timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.discarding());
            System.out.println("[+] Local reachability: HTTP " + probe.statusCode() + " (this URL is fetchable from this host)");
        } catch (Exception probeEx) {
            System.out.println("[!] Local reachability FAILED: " + probeEx.getClass().getSimpleName() + ": " + probeEx.getMessage());
            System.out.println("    The wallet's KERIA cannot resolve this OOBI either.");
        }
        System.out.println("⚠ If Veridian runs in Docker, the hostname must also be");
        System.out.println("  reachable from the wallet's container (try `host.docker.internal`");
        System.out.println("  or the host's LAN IP, set via KERIA's --http argument).");
        System.out.println("======================================================");
        System.out.print("Press <Enter> once Veridian has resolved the above OOBI… ");
        sc.nextLine();

        System.out.println("[*] Resolving partner OOBI…");
        var resolveOp = client.oobis().resolve(oobi, "askkel-partner");
        var resolved = client.operations().wait(Operation.fromObject(resolveOp));
        @SuppressWarnings("unchecked")
        var resp = (Map<String, Object>) Operation.fromObject(resolved).getResponse();
        var partnerAid = (String) resp.get("i");
        System.out.println("[+] Partner AID:  " + partnerAid);
        System.out.println("[+] OOBI resolve response keys: " + resp.keySet());

        // Allow the recipient AID to be overridden via env (e.g. the wallet exposes a
        // dedicated remotesign AID that isn't the OOBI controller AID); otherwise use
        // the AID we just learned from resolving the OOBI.
        var recipient = env("RECIPIENT_AID", partnerAid);

        // Build the SAIDified `a` payload. CRITICAL: signify-java's createExchangeMessage
        // (Exchanging.java:228) injects `attrs.put("i", recipient)` BEFORE merging our
        // payload. If we leave `i` out, our SAID is for {d, name, surname} but the SAID
        // Veridian recomputes is for {i, d, name, surname} → mismatch → wallet silently
        // drops the notification (processRemoteSignReq.markNotification, never surfaces UI).
        // Inserting `i` first ourselves keeps both SAIDs identical.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("i", recipient);
        payload.put("d", "");
        payload.put("name", "John");
        payload.put("surname", "Smith");
        var ked = Saider.saidify(payload).sad();
        System.out.println("[*] SAID digest: " + ked.get("d"));
        System.out.println();
        System.out.println("[*] Sending /remotesign/ixn/req:");
        System.out.println("      from (sender)    = " + hab.getPrefix());
        System.out.println("      to   (recipient) = " + recipient);
        System.out.println("      route            = /remotesign/ixn/req");
        System.out.println("      payload (a)      = " + ked);
        var sendResponse = client.exchanges().send(NAME, "remotesign", hab,
                "/remotesign/ixn/req", ked, Map.of(), List.of(recipient));
        System.out.println("[+] KERIA send response: " + sendResponse);

        // Record the SAID we want a reply for. The reply's exn has p = <reqSaid>.
        @SuppressWarnings("unchecked")
        var sendRespMap = (Map<String, Object>) sendResponse;
        var reqSaid = sendRespMap != null ? (String) sendRespMap.get("d") : (String) ked.get("d");

        // Drain stale /remotesign/* notifications from previous runs so the wait loop
        // isn't confused by leftover /req notifications that Veridian hasn't processed.
        drainStaleRemotesignNotifications(client, reqSaid);

        System.out.println("[*] Approve the prompt in Veridian for req SAID=" + ked.get("d"));
        System.out.println("[*] Polling for /remotesign/ixn/ref (matching p=" + reqSaid + ")…");

        // KERIA prepends "/exn/" to the route when surfacing inbound exn messages as
        // notifications. The exn body's r field is "/remotesign/ixn/ref"; the notification
        // body's r is "/exn/remotesign/ixn/ref". Match both to be safe.
        var note = waitForNote(client, "/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");
        client.notifications().mark(note.i);
        client.notifications().delete(note.i);
        System.out.println("[+] Wallet approved.");

        // The wallet's /ixn/ref reply means it has accepted and signed the request, but
        // the new ixn event still has to propagate through witnesses before keyStates().query
        // can return the updated state. Sleep briefly and retry a few times — KERIA also
        // tends to reset the keep-alive socket if we hammer it immediately after the reply.
        String seqNumber = "<unknown>";
        Thread.sleep(2000);
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                // signify-java's query signature is (pre, sn) — pre is the prefix to query,
                // sn is an optional hex sequence number. Passing the AID as the second arg
                // makes KERIA do `int(sn, 16)` and crash. Use null for "latest state".
                var ksOp = client.keyStates().query(recipient, null);
                client.operations().wait(Operation.fromObject(ksOp));
                var raw = client.keyStates().get(recipient);
                if (raw.isPresent()) {
                    // KERIA's /states?pre=X returns a LIST of key-state dicts. Older
                    // versions / single-result paths sometimes return a bare map.
                    Map<String, Object> ks = coerceKeyState(raw.get());
                    if (ks != null) {
                        Object s = ks.get("s");
                        if (s != null) {
                            seqNumber = s.toString();
                            break;
                        }
                    }
                }
                System.out.println("[…] Key state not yet available (attempt " + attempt + "/5)");
            } catch (Exception keyStateEx) {
                System.out.println("[…] keyStates query failed on attempt " + attempt + "/5: "
                        + keyStateEx.getClass().getSimpleName()
                        + (keyStateEx.getMessage() != null ? ": " + keyStateEx.getMessage() : "")
                        + " — retrying");
            }
            Thread.sleep(3000);
        }
        if ("<unknown>".equals(seqNumber)) {
            System.out.println("[!] Gave up trying to fetch the new key state after 5 attempts.");
            System.out.println("    The interact event still landed in the wallet's KEL — the wallet's reply confirmed it.");
        }

        System.out.println();
        System.out.println("KEL extended ✓");
        System.out.println("  AID:    " + recipient);
        System.out.println("  Digest: " + ked.get("d"));
        System.out.println("  Seq #:  " + seqNumber);
    }

    /**
     * Reuse an existing identifier or create a new one with witnesses + agent end-role.
     * Without witnesses + end-role the AID has no OOBI Veridian's KERIA can resolve, so
     * the mutual OOBI exchange (which is required — see processNotification gate in
     * Veridian's keriaNotificationService.ts) silently fails. Mirrors the logic used by
     * KeriConfig.createAid in the Spring backend. The end-role check is unconditional
     * so existing identifiers created without the role get patched up on next run.
     */
    static States.HabState ensureIdentifier(SignifyClient client) throws Exception {
        var existing = client.identifiers().get(NAME);
        if (existing.isEmpty()) {
            System.out.println("[*] Creating identifier '" + NAME + "' with witnesses…");
            var witnesses = pickWitnesses(client);
            var args = CreateIdentifierArgs.builder().build();
            args.setToad(witnesses.toad);
            args.setWits(witnesses.eids);

            EventResult er = client.identifiers().create(NAME, args);
            client.operations().wait(Operation.fromObject(er.op()));
        }

        ensureAgentEndRole(client);
        return client.identifiers().get(NAME).orElseThrow();
    }

    /** Add the agent end-role for our identifier if it's not registered yet. */
    @SuppressWarnings("unchecked")
    static void ensureAgentEndRole(SignifyClient client) throws Exception {
        String agentEid = client.getAgent() != null ? client.getAgent().getPre() : null;
        if (agentEid == null || agentEid.isEmpty()) {
            throw new IllegalStateException("KERIA agent EID is not available — connect()/boot() may have failed");
        }

        var endRolesResp = client.fetch("/identifiers/" + NAME + "/endroles/agent", "GET", NAME, null);
        var roles = Utils.fromJson(endRolesResp.body(), new TypeReference<List<Map<String, Object>>>() {});
        boolean alreadyRegistered = roles.stream()
                .anyMatch(r -> "agent".equals(r.get("role")) && agentEid.equals(r.get("eid")));

        if (alreadyRegistered) return;

        System.out.println("[*] Registering agent end-role for eid=" + agentEid);
        EventResult roleRes = client.identifiers().addEndRole(NAME, "agent", agentEid, null);
        client.operations().wait(Operation.fromObject(roleRes.op()));
        System.out.println("[+] Agent end-role registered");
    }

    /** Pull the agent OOBI URL for our identifier so we can hand it to the wallet. */
    @SuppressWarnings("unchecked")
    static String fetchOurOobi(SignifyClient client) throws Exception {
        Optional<Object> result = client.oobis().get(NAME, "agent");
        if (result.isEmpty()) throw new IllegalStateException("No OOBI returned for " + NAME);
        var body = (Map<String, Object>) result.get();
        var oobis = (List<String>) body.get("oobis");
        if (oobis == null || oobis.isEmpty()) {
            throw new IllegalStateException("Agent end-role not registered yet — OOBI list empty");
        }
        return oobis.get(0);
    }

    /** Read iurls from agent config and pick up to 6 witnesses (toad = 4 if ≥6). */
    @SuppressWarnings("unchecked")
    static Witnesses pickWitnesses(SignifyClient client) throws Exception {
        Map<String, Object> config = (Map<String, Object>) new Coring.Config(client).get();
        List<String> iurls = (List<String>) config.get("iurls");
        if (iurls == null || iurls.isEmpty()) {
            throw new IllegalStateException("KERIA agent config has no iurls — cannot pick witnesses");
        }
        var unique = new LinkedHashMap<String, String>();
        for (String oobi : iurls) {
            try {
                new URL(oobi);
                String[] parts = oobi.split("/oobi/");
                if (parts.length > 1) unique.putIfAbsent(parts[1].split("/")[0], oobi);
            } catch (Exception ignored) {}
        }
        var eids = new ArrayList<>(unique.keySet());
        if (eids.size() >= 6) return new Witnesses(4, eids.subList(0, 6));
        return new Witnesses(eids.size(), eids);
    }

    record Witnesses(int toad, List<String> eids) {}

    /** Handle both list-of-maps and bare-map shapes from KERIA's /states endpoint. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> coerceKeyState(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof List) {
            var list = (List<Object>) raw;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                return (Map<String, Object>) list.get(0);
            }
        }
        return null;
    }

    /**
     * Remove pending /remotesign/* notifications from previous runs so the wait loop
     * isn't confused. We keep our own current request (whose SAID we know) just in case.
     */
    static void drainStaleRemotesignNotifications(SignifyClient client, String currentReqSaid) throws Exception {
        Notifying.Notifications.NotificationListResponse response = client.notifications().list();
        List<IpexNote> notes = Utils.fromJson(response.notes(), new TypeReference<>() {});
        int drained = 0;
        for (var n : notes) {
            if (n.a == null || n.a.r == null) continue;
            if (!n.a.r.startsWith("/exn/remotesign/") && !n.a.r.startsWith("/remotesign/")) continue;
            if (currentReqSaid != null && currentReqSaid.equals(n.a.d)) continue; // keep ours
            try {
                client.notifications().mark(n.i);
                client.notifications().delete(n.i);
                drained++;
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        if (drained > 0) {
            System.out.println("[*] Drained " + drained + " stale /remotesign/* notifications from previous runs.");
        }
    }

    static IpexNote waitForNote(SignifyClient client, String... acceptedRoutes) throws Exception {
        // 5 minutes — plenty of time for the user to find Veridian, see the prompt, and approve.
        final int maxAttempts = 150;
        final long pollIntervalMs = 2000;
        var accepted = List.of(acceptedRoutes);
        for (int i = 0; i < maxAttempts; i++) {
            Notifying.Notifications.NotificationListResponse response = client.notifications().list();
            List<IpexNote> notes = Utils.fromJson(response.notes(), new TypeReference<>() {});
            var match = notes.stream()
                    .filter(n -> n.a != null && accepted.contains(n.a.r) && !Boolean.TRUE.equals(n.r))
                    .findFirst();
            if (match.isPresent()) return match.get();
            // Reduce noise — print only every 5 ticks, and include unread routes for debug.
            if (i % 5 == 0) {
                var unread = notes.stream()
                        .filter(n -> !Boolean.TRUE.equals(n.r))
                        .map(n -> n.a == null ? "<no body>" : n.a.r)
                        .toList();
                int secondsElapsed = (int) (i * pollIntervalMs / 1000);
                System.out.println("    …waiting for wallet approval (" + secondsElapsed + "s elapsed); unread routes=" + unread);
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new RuntimeException("Timed out waiting for " + accepted + " after "
                + (maxAttempts * pollIntervalMs / 1000) + "s");
    }

    public static class IpexNote {
        public String i;
        public Boolean r;
        public Body a;
        public static class Body { public String r; public String d; }
    }

    static String env(String key, String def) {
        var v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
