package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.SchemaConfig;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.keri.*;
import org.cardanofoundation.cip113.repository.KycSessionRepository;
import org.cardanofoundation.cip113.service.KycProofService;
import org.cardanofoundation.cip113.util.IpexNotificationHelper;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialData;
import org.cardanofoundation.signify.app.credentialing.credentials.IssueCredentialResult;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAgreeArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexApplyArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexGrantArgs;
import org.cardanofoundation.signify.app.credentialing.registries.CreateRegistryArgs;
import org.cardanofoundation.signify.app.credentialing.registries.RegistryResult;
import org.cardanofoundation.signify.cesr.Serder;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;
import org.cardanofoundation.signify.cesr.util.Utils;
import org.cardanofoundation.signify.core.States;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("${apiPrefix}/keri")
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class KeriController {

    private final IdentifierConfig identifierConfig;
    private final SignifyClient client;
    private final KycSessionRepository kycSessionRepository;
    private final KycProofService kycProofService;
    private final SchemaConfig schemaConfig;
    private final ObjectMapper objectMapper;
    private final org.cardanofoundation.cip113.service.AccountService accountService;
    private final com.bloxbean.cardano.client.quicktx.QuickTxBuilder quickTxBuilder;

    private static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");
    private static final DateTimeFormatter KERI_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");

    @Value("${keri.identifier.name}")
    private String identifierName;

    @Value("${keri.identifier.registry-name:kyc-registry}")
    private String registryName;

    @Value("${keri.signing-mnemonic}")
    private String signingMnemonic;

    @Value("${network:preview}")
    private String network;

    private final ConcurrentHashMap<String, Thread> activePresentations = new ConcurrentHashMap<>();

    // ── Signing entity key ─────────────────────────────────────────────────────

    @GetMapping("/signing-entity-vkey")
    public ResponseEntity<?> getSigningEntityVkey() {
        try {
            var networkInfo = switch (network) {
                case "mainnet" -> com.bloxbean.cardano.client.common.model.Networks.mainnet();
                case "preprod" -> com.bloxbean.cardano.client.common.model.Networks.preprod();
                default -> com.bloxbean.cardano.client.common.model.Networks.preview();
            };
            var entityAccount = com.bloxbean.cardano.client.account.Account.createFromMnemonic(
                    networkInfo, signingMnemonic);
            String vkeyHex = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                    entityAccount.publicKeyBytes());
            return ResponseEntity.ok(Map.of("vkeyHex", vkeyHex));
        } catch (Exception e) {
            log.error("Failed to derive signing entity vkey", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── OOBI ──────────────────────────────────────────────────────────────────

    @GetMapping("/oobi")
    public ResponseEntity<OobiResponse> getOobi(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) throws Exception {
        Optional<Object> o = client.oobis().get(identifierConfig.getName(), null);
        if (o.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> oobiMap = (Map<String, Object>) o.get();
        @SuppressWarnings("unchecked")
        List<String> oobis = (List<String>) oobiMap.get("oobis");
        if (oobis == null || oobis.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new OobiResponse(oobis.getFirst()));
    }

    @GetMapping("/oobi/resolve")
    public ResponseEntity<Boolean> resolveOobi(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam String oobi) throws Exception {
        Object resolve = client.oobis().resolve(oobi, sessionId);
        var wait = client.operations().wait(Operation.fromObject(resolve));

        if (wait.isDone()) {
            Matcher matcher = OOBI_AID_PATTERN.matcher(URI.create(oobi).getPath());
            if (!matcher.find()) {
                throw new IllegalArgumentException("No AID found in OOBI URL: " + oobi);
            }
            String aid = matcher.group(1);
            client.contacts().get(aid);
            KycSessionEntity entity = KycSessionEntity.builder()
                    .sessionId(sessionId)
                    .oobi(oobi)
                    .aid(aid)
                    .build();
            kycSessionRepository.save(entity);
            return ResponseEntity.ok(true);
        } else {
            return ResponseEntity.internalServerError().body(false);
        }
    }

    // ── Schema discovery ──────────────────────────────────────────────────────

    @GetMapping("/schemas")
    public ResponseEntity<SchemaListResponse> getSchemas() {
        if (schemaConfig.getSchemas() == null) {
            return ResponseEntity.ok(new SchemaListResponse(List.of()));
        }
        List<SchemaItem> schemas = new ArrayList<>();
        for (Map.Entry<String, SchemaConfig.SchemaEntry> entry : schemaConfig.getSchemas().entrySet()) {
            try {
                Role role = Role.fromString(entry.getKey());
                schemas.add(new SchemaItem(entry.getKey(), role.getValue(),
                        entry.getValue().getLabel(), entry.getValue().getSaid()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown role name in schema config: {}", entry.getKey());
            }
        }
        schemas.sort(Comparator.comparingInt(SchemaItem::roleValue));
        return ResponseEntity.ok(new SchemaListResponse(schemas));
    }

    @GetMapping("/available-roles")
    public ResponseEntity<?> getAvailableRoles() {
        if (schemaConfig.getSchemas() == null) {
            return ResponseEntity.ok(Map.of("availableRoles", List.of()));
        }
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Map.Entry<String, SchemaConfig.SchemaEntry> entry : schemaConfig.getSchemas().entrySet()) {
            try {
                Role role = Role.fromString(entry.getKey());
                roles.add(Map.of(
                        "role", entry.getKey(),
                        "roleValue", role.getValue(),
                        "label", entry.getValue().getLabel()
                ));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown role name in schema config: {}", entry.getKey());
            }
        }
        roles.sort(Comparator.comparingInt(r -> (int) r.get("roleValue")));
        return ResponseEntity.ok(Map.of("availableRoles", roles));
    }

    // ── IPEX credential exchange ──────────────────────────────────────────────

    @GetMapping("/credential/present")
    public ResponseEntity<?> presentCredential(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "role", defaultValue = "USER") String roleName) throws Exception {
        Optional<KycSessionEntity> kycEntity = kycSessionRepository.findById(sessionId);
        if (kycEntity.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Role role;
        try {
            role = Role.fromString(roleName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown role: " + roleName));
        }

        SchemaConfig.SchemaEntry schemaEntry = schemaConfig.getSchemaForRole(role);
        if (schemaEntry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No schema configured for role: " + roleName));
        }

        String aid = kycEntity.get().getAid();

        activePresentations.put(sessionId, Thread.currentThread());
        try {
            // Step 1: Send /ipex/apply
            IpexApplyArgs applyArgs = IpexApplyArgs.builder()
                    .recipient(aid)
                    .senderName(identifierName)
                    .schemaSaid(schemaEntry.getSaid())
                    .attributes(Map.of("oobiUrl", schemaConfig.getBaseUrl()))
                    .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                    .build();
            Exchanging.ExchangeMessageResult applyResult = client.ipex().apply(applyArgs);
            Object applyOp = client.ipex().submitApply(identifierName, applyResult.exn(),
                    applyResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(applyOp));

            // Step 2: Wait for /ipex/offer
            log.info("Waiting for wallet to respond with an offer...");
            IpexNotificationHelper.Notification offerNote =
                    IpexNotificationHelper.waitForNotification(client, "/exn/ipex/offer");

            @SuppressWarnings("unchecked")
            Map<String, Object> offerExn = (Map<String, Object>)
                    ((LinkedHashMap<String, Object>) client.exchanges().get(offerNote.a.d).get()).get("exn");

            String offerSaid = offerExn.get("d").toString();
            IpexNotificationHelper.markAndDelete(client, offerNote);

            // Step 3: Send /ipex/agree
            IpexAgreeArgs agreeArgs = IpexAgreeArgs.builder()
                    .senderName(identifierName)
                    .recipient(aid)
                    .offerSaid(offerSaid)
                    .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                    .build();
            Exchanging.ExchangeMessageResult agreeResult = client.ipex().agree(agreeArgs);
            Object agreeOp = client.ipex().submitAgree(identifierName, agreeResult.exn(),
                    agreeResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(agreeOp));

            // Step 4: Wait for /ipex/grant
            IpexNotificationHelper.Notification grantNote =
                    IpexNotificationHelper.waitForNotification(client, "/exn/ipex/grant");

            @SuppressWarnings("unchecked")
            Map<String, Object> grantExn = (Map<String, Object>)
                    ((Map<String, Object>) client.exchanges().get(grantNote.a.d).get()).get("exn");

            IpexAdmitArgs admitArgs = IpexAdmitArgs.builder()
                    .senderName(identifierName)
                    .recipient(aid)
                    .grantSaid(grantExn.get("d").toString())
                    .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                    .message("")
                    .build();
            Exchanging.ExchangeMessageResult admit = client.ipex().admit(admitArgs);
            Object o = client.ipex().submitAdmit(identifierName, admit.exn(), admit.sigs(),
                    agreeResult.atc(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(o));
            IpexNotificationHelper.markAndDelete(client, grantNote);

            // Extract ACDC attributes
            @SuppressWarnings("unchecked")
            Map<String, Object> acdc = (Map<String, Object>)
                    ((Map<String, Object>) grantExn.get("e")).get("acdc");
            @SuppressWarnings("unchecked")
            Map<String, Object> rawAttributes = (Map<String, Object>) acdc.get("a");
            Map<String, Object> userAttributes = new LinkedHashMap<>(rawAttributes);
            userAttributes.remove("i");

            // Persist credential
            KycSessionEntity entity = kycEntity.get();
            try {
                entity.setCredentialAid(acdc.get("d").toString());
                entity.setCredentialSaid(schemaEntry.getSaid());
                entity.setCredentialAttributes(objectMapper.writeValueAsString(userAttributes));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize credential attributes", e);
            }
            entity.setCredentialRole(role.getValue());
            kycSessionRepository.save(entity);

            return ResponseEntity.ok(new CredentialResponse(role.name(), role.getValue(),
                    schemaEntry.getLabel(), userAttributes));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(409).body(Map.of("error", "Presentation cancelled."));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Timed out")) {
                return ResponseEntity.status(408)
                        .body(Map.of("error", "No credential was received — the wallet did not respond in time."));
            }
            throw e;
        } finally {
            activePresentations.remove(sessionId);
        }
    }

    @PostMapping("/credential/cancel")
    public ResponseEntity<?> cancelPresentation(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-Session-Id header required"));
        }
        Thread t = activePresentations.get(sessionId);
        if (t != null) {
            t.interrupt();
        }
        return ResponseEntity.ok(Map.of("cancelled", t != null));
    }

    // ── Credential issuance ────────────────────────────────────────────────────

    @PostMapping("/credential/issue")
    public ResponseEntity<?> issueCredential(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, String> body) {
        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "X-Session-Id header required"));
        }
        Optional<KycSessionEntity> kycEntityOpt = kycSessionRepository.findById(sessionId);
        if (kycEntityOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String firstName = body.get("firstName");
        String lastName = body.get("lastName");
        String email = body.get("email");
        if (firstName == null || lastName == null || email == null ||
                firstName.isBlank() || lastName.isBlank() || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "firstName, lastName and email are required"));
        }

        Role role = Role.USER;
        SchemaConfig.SchemaEntry schemaEntry = schemaConfig.getSchemaForRole(role);
        if (schemaEntry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No USER schema configured"));
        }

        KycSessionEntity kycEntity = kycEntityOpt.get();
        String walletAid = kycEntity.getAid();
        String datetime = KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC));

        try {
            String registrySaid = getOrCreateRegistrySaid();

            Map<String, Object> additionalProps = new LinkedHashMap<>();
            additionalProps.put("firstName", firstName);
            additionalProps.put("lastName", lastName);
            additionalProps.put("email", email);

            CredentialData.CredentialSubject subject = CredentialData.CredentialSubject.builder()
                    .i(walletAid)
                    .dt(datetime)
                    .additionalProperties(additionalProps)
                    .build();

            CredentialData credentialData = CredentialData.builder()
                    .ri(registrySaid)
                    .s(schemaEntry.getSaid())
                    .a(subject)
                    .build();

            IssueCredentialResult issueResult = client.credentials().issue(identifierName, credentialData);
            client.operations().wait(Operation.fromObject(issueResult.getOp()));

            String credentialSaid = issueResult.getAcdc().getKed().get("d").toString();
            log.info("Issued credential SAID={} for session={}", credentialSaid, sessionId);

            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Object> credentialMap = (LinkedHashMap<String, Object>)
                    client.credentials().get(credentialSaid, false)
                            .orElseThrow(() -> new IllegalStateException("Issued credential not found: " + credentialSaid));

            @SuppressWarnings("unchecked")
            Map<String, Object> sad = (Map<String, Object>) credentialMap.get("sad");
            @SuppressWarnings("unchecked")
            Map<String, Object> anc = (Map<String, Object>) credentialMap.get("anc");
            @SuppressWarnings("unchecked")
            Map<String, Object> iss = (Map<String, Object>) credentialMap.get("iss");
            @SuppressWarnings("unchecked")
            List<String> ancatc = (List<String>) credentialMap.get("ancatc");
            String ancAttachment = (ancatc != null && !ancatc.isEmpty()) ? ancatc.getFirst() : null;

            IpexGrantArgs grantArgs = IpexGrantArgs.builder()
                    .senderName(identifierName)
                    .recipient(walletAid)
                    .datetime(KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC)))
                    .acdc(new Serder(sad))
                    .iss(new Serder(iss))
                    .anc(new Serder(anc))
                    .ancAttachment(ancAttachment)
                    .build();
            Exchanging.ExchangeMessageResult grantResult = grantCredential(grantArgs,
                    schemaConfig.getBaseUrl(), schemaEntry.getSaid());
            Object grantOp = client.ipex().submitGrant(identifierName, grantResult.exn(),
                    grantResult.sigs(), grantResult.atc(), Collections.singletonList(walletAid));
            client.operations().wait(Operation.fromObject(grantOp));

            log.info("IPEX grant submitted for credential SAID={}", credentialSaid);

            kycEntity.setCredentialAid(credentialSaid);
            kycEntity.setCredentialSaid(schemaEntry.getSaid());
            try {
                kycEntity.setCredentialAttributes(objectMapper.writeValueAsString(additionalProps));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize credential attributes", e);
            }
            kycEntity.setCredentialRole(role.getValue());
            kycSessionRepository.save(kycEntity);

            return ResponseEntity.ok(new CredentialResponse(role.name(), role.getValue(),
                    schemaEntry.getLabel(), additionalProps));

        } catch (Exception e) {
            log.error("credential/issue failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Session state ─────────────────────────────────────────────────────────

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> getSession(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null) {
            return ResponseEntity.ok(SessionResponse.builder().exists(false).build());
        }
        Optional<KycSessionEntity> opt = kycSessionRepository.findById(sessionId);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(SessionResponse.builder().exists(false).build());
        }
        KycSessionEntity kyc = opt.get();
        boolean hasCredential = kyc.getCredentialAttributes() != null;
        boolean hasCardanoAddress = kyc.getCardanoAddress() != null;

        SessionResponse.SessionResponseBuilder builder = SessionResponse.builder()
                .exists(true)
                .hasCredential(hasCredential)
                .hasCardanoAddress(hasCardanoAddress);

        if (hasCredential) {
            builder.attributes(resolveAttributes(kyc));
            builder.credentialRole(kyc.getCredentialRole() != null ? kyc.getCredentialRole() : 0);
            if (kyc.getCredentialRole() != null) {
                try {
                    builder.credentialRoleName(Role.fromValue(kyc.getCredentialRole()).name());
                } catch (IllegalArgumentException e) {
                    builder.credentialRoleName("USER");
                }
            }
        }
        if (hasCardanoAddress) {
            builder.cardanoAddress(kyc.getCardanoAddress());
        }
        if (kyc.getKycProofPayload() != null) {
            builder.kycProofPayload(kyc.getKycProofPayload())
                    .kycProofSignature(kyc.getKycProofSignature())
                    .kycProofEntityVkey(kyc.getKycProofEntityVkey())
                    .kycProofValidUntil(kyc.getKycProofValidUntil());
        }
        return ResponseEntity.ok(builder.build());
    }

    @PostMapping("/session/cardano-address")
    public ResponseEntity<?> storeCardanoAddress(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, String> body) {
        if (sessionId == null || !kycSessionRepository.existsById(sessionId)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        String cardanoAddress = body.get("cardanoAddress");
        if (cardanoAddress == null || cardanoAddress.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cardanoAddress is required"));
        }
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId).get();
        kyc.setCardanoAddress(cardanoAddress);
        kycSessionRepository.save(kyc);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── KYC Proof generation ──────────────────────────────────────────────────

    @PostMapping("/kyc-proof/generate")
    public ResponseEntity<?> generateKycProof(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null || !kycSessionRepository.existsById(sessionId)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId).get();
        String userAddress = kyc.getCardanoAddress();
        if (userAddress == null || userAddress.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "No Cardano address on record — please connect your wallet first"));
        }
        if (kyc.getCredentialRole() == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "No credential on record — please present a credential first"));
        }

        try {
            var proof = kycProofService.generateProof(userAddress, kyc.getCredentialRole());

            kyc.setKycProofPayload(proof.payloadHex());
            kyc.setKycProofSignature(proof.signatureHex());
            kyc.setKycProofEntityVkey(proof.entityVkeyHex());
            kyc.setKycProofValidUntil(proof.validUntilPosixMs());
            kycSessionRepository.save(kyc);

            return ResponseEntity.ok(proof);
        } catch (Exception e) {
            log.error("kyc-proof/generate failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── CIP-170 Credential Chain Publishing & Attestation ───────────────────────

    @PostMapping("/credential-chain/publish")
    public ResponseEntity<?> publishCredentialChain(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody CredentialChainPublishRequest request) {
        if (sessionId == null || !kycSessionRepository.existsById(sessionId)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId).get();
        if (kyc.getCredentialAid() == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "No credential on record — please present a credential first"));
        }

        try {
            // Fetch full CESR credential chain
            String credentialSaid = kyc.getCredentialAid();
            
            Optional<String> cesrOpt = client.credentials().getCESR(credentialSaid);
            if (cesrOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Credential chain not found for SAID: " + credentialSaid));
            }
            String cesrChain = cesrOpt.get();
            List<Map<String, Object>> cesrData = CESRStreamUtil.parseCESRData(cesrChain);
            String strippedCesrChain = strip(cesrData);
            byte[][] chunks = splitIntoChunks(strippedCesrChain.getBytes(), 64);
            MetadataList credentialChunks = MetadataBuilder.createList();
            for (byte[] chunk : chunks) {
                credentialChunks.add(chunk);
            }
            // Build CIP-170 AUTH_BEGIN metadata at label 170
            var cip170Map = com.bloxbean.cardano.client.metadata.MetadataBuilder.createMap();
            cip170Map.put("t", "AUTH_BEGIN");
            cip170Map.put("i", kyc.getAid());
            cip170Map.put("s", kyc.getCredentialSaid());
            // Credential chain as CESR string
            cip170Map.put("c", credentialChunks);

            var versionMap = com.bloxbean.cardano.client.metadata.MetadataBuilder.createMap();
            versionMap.put("v", "1.0");
            versionMap.put("k", "KERI10JSON");
            versionMap.put("a", "ACDC10JSON");
            cip170Map.put("v", versionMap);

            var metadata = com.bloxbean.cardano.client.metadata.MetadataBuilder.createMetadata();
            metadata.put(170L, cip170Map);

            // Build simple transaction carrying only the AUTH_BEGIN metadata.
            // payToAddress(self, 1 ADA) ensures at least one output; the rest is change.
            var tx = new com.bloxbean.cardano.client.quicktx.Tx()
                    .from(request.feePayerAddress())
                    .payToAddress(request.feePayerAddress(), com.bloxbean.cardano.client.api.model.Amount.ada(1))
                    .attachMetadata(metadata)
                    .withChangeAddress(request.feePayerAddress());

            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(request.feePayerAddress())
                    .mergeOutputs(true)
                    .build();

            log.info("CIP-170 AUTH_BEGIN tx built for session={}, signer={}", sessionId, kyc.getAid());
            return ResponseEntity.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("credential-chain/publish failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    private byte[][] splitIntoChunks(byte[] data, int chunkSize) {
        int numChunks = (data.length + chunkSize - 1) / chunkSize;
        byte[][] chunks = new byte[numChunks][];

        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, data.length);
            chunks[i] = Arrays.copyOfRange(data, start, end);
        }

        return chunks;
    }

    private String strip(List<Map<String, Object>> cesrData) {
        List<Map<String, Object>> allVcpEvents = new ArrayList<>();
        List<String> allVcpAttachments = new ArrayList<>();
        List<Map<String, Object>> allIssEvents = new ArrayList<>();
        List<String> allIssAttachments = new ArrayList<>();
        List<Map<String, Object>> allAcdcEvents = new ArrayList<>();
        List<String> allAcdcAttachments = new ArrayList<>();

        for (Map<String, Object> eventData : cesrData) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");

            // Check for event type
            Object eventTypeObj = event.get("t");
            if (eventTypeObj != null) {
                String eventType = eventTypeObj.toString();
                switch (eventType) {
                    case "vcp":
                        allVcpEvents.add(event);
                        allVcpAttachments.add((String) eventData.get("atc"));
                        break;
                    case "iss":
                        allIssEvents.add(event);
                        allIssAttachments.add((String) eventData.get("atc"));
                        break;
                }
            } else {
                // Check if this is an ACDC (credential data) without "t" field
                if (event.containsKey("s") && event.containsKey("a") && event.containsKey("i")) {
                    Object schemaObj = event.get("s");
                    if (schemaObj != null) {
                        allAcdcEvents.add(event);
                        allAcdcAttachments.add("");
                    }
                }
            }
        }

        List<Map<String, Object>> combinedEvents = new ArrayList<>();
        List<String> combinedAttachments = new ArrayList<>();

        combinedEvents.addAll(allVcpEvents);
        combinedEvents.addAll(allIssEvents);
        combinedEvents.addAll(allAcdcEvents);

        combinedAttachments.addAll(allVcpAttachments);
        combinedAttachments.addAll(allIssAttachments);
        combinedAttachments.addAll(allAcdcAttachments);

        return CESRStreamUtil.makeCESRStream(combinedEvents, combinedAttachments);
    }

    @PostMapping("/attest/request")
    public ResponseEntity<?> requestAttestation(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody AttestAnchorRequest request) {
        if (sessionId == null || !kycSessionRepository.existsById(sessionId)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId).get();
        String userAid = kyc.getAid();
        if (userAid == null || userAid.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "No AID on record — please complete OOBI exchange first"));
        }

        try {
            // Build payload and compute SAID.
            // CRITICAL: signify-java's createExchangeMessage (Exchanging.java:228) does
            // `attrs.put("i", recipient); attrs.putAll(payload)` BEFORE the wire send. If we
            // omit `i` from the payload here, our pre-computed SAID is for {d, unit, quantity}
            // but the SAID Veridian recomputes is for {i, d, unit, quantity} → mismatch →
            // wallet's processRemoteSignReq calls markNotification and silently drops the
            // request without surfacing UI. Inserting `i` first ourselves keeps the SAIDs in sync.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("i", userAid);
            payload.put("d", "");
            payload.put("unit", request.unit());
            payload.put("quantity", request.quantity());

            var saidifyResult = org.cardanofoundation.signify.cesr.Saider.saidify(payload);
            Map<String, Object> ked = saidifyResult.sad();
            String digest = (String) ked.get("d");

            log.info("SAID computed for attestation: digest={}, unit={}, quantity={}",
                    digest, request.unit(), request.quantity());

            // Send remotesign exchange message to user's wallet
            var hab = client.identifiers().get(identifierName)
                    .orElseThrow(() -> new IllegalStateException("Identifier not found: " + identifierName));

            client.exchanges().send(identifierName, "remotesign",
                    hab, "/remotesign/ixn/req", ked, Map.of(), List.of(userAid));

            log.info("Remotesign ixn request sent to wallet AID={}, digest={}", userAid, digest);

            // Wait for the wallet to respond on /remotesign/ixn/ref. KERIA prefixes inbound
            // exn routes with "/exn/" when surfacing them as notifications, so we have to
            // accept both "/remotesign/ixn/ref" and "/exn/remotesign/ixn/ref".
            IpexNotificationHelper.Notification refNote =
                    IpexNotificationHelper.waitForNotification(client,
                            "/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");
            IpexNotificationHelper.markAndDelete(client, refNote);

            // Query user's key state to get the new sequence number after the interact event.
            // signify-java's query signature is (pre, sn) — the second arg is an OPTIONAL hex
            // sequence-number string; passing the AID there makes KERIA do `int(aid, 16)` and
            // crash. Use null for "latest state". Also, KERIA's /states?pre=X returns a LIST
            // of key-state dicts — coerceKeyState handles both list and bare-map shapes.
            String seqNumber = "<unknown>";
            Thread.sleep(2000); // let the new ixn settle in KERIA before querying
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    Object queryOp = client.keyStates().query(userAid, null);
                    client.operations().wait(Operation.fromObject(queryOp));
                    Optional<Object> raw = client.keyStates().get(userAid);
                    if (raw.isPresent()) {
                        Map<String, Object> ks = coerceKeyState(raw.get());
                        if (ks != null && ks.get("s") != null) {
                            seqNumber = ks.get("s").toString();
                            break;
                        }
                    }
                    log.info("Key state for {} not available yet (attempt {}/5)", userAid, attempt);
                } catch (Exception ex) {
                    log.warn("keyStates query attempt {}/5 failed: {} — retrying", attempt, ex.toString());
                }
                Thread.sleep(3000);
            }

            var attestation = new Cip170AttestationData(userAid, digest, seqNumber, "1.0");
            log.info("CIP-170 attestation anchored: signer={}, digest={}, seq={}",
                    userAid, digest, seqNumber);

            return ResponseEntity.ok(attestation);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(409).body(Map.of("error", "Attestation request cancelled."));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Timed out")) {
                return ResponseEntity.status(408).body(
                        Map.of("error", "Wallet did not respond to anchor request in time."));
            }
            throw e;
        } catch (Exception e) {
            log.error("attest/request failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveAttributes(KycSessionEntity kyc) {
        if (kyc.getCredentialAttributes() != null) {
            try {
                return objectMapper.readValue(kyc.getCredentialAttributes(), Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse credential attributes for session={}", kyc.getSessionId(), e);
                return Map.of();
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private String getOrCreateRegistrySaid() throws Exception {
        List<Map<String, Object>> registries =
                (List<Map<String, Object>>) client.registries().list(identifierName);
        if (registries != null && !registries.isEmpty()) {
            return registries.getFirst().get("regk").toString();
        }
        log.info("No credential registry found, creating '{}'", registryName);
        CreateRegistryArgs args = CreateRegistryArgs.builder()
                .name(identifierName)
                .registryName(registryName)
                .noBackers(true)
                .build();
        RegistryResult result = client.registries().create(args);
        @SuppressWarnings("unchecked")
        Map<String, Object> opMap = objectMapper.readValue(result.op(), Map.class);
        client.operations().wait(Operation.fromObject(opMap));
        return result.getRegser().getPre();
    }

    private Exchanging.ExchangeMessageResult grantCredential(IpexGrantArgs args,
                                                              String schemaUrl,
                                                              String schemaSAID) throws Exception {
        States.HabState hab = client.identifiers().get(args.getSenderName())
                .orElseThrow(() -> new IllegalArgumentException("Identifier not found: " + args.getSenderName()));

        if (args.getAncAttachment() == null) {
            throw new IllegalStateException("ancatc is missing from credential — was the issuance operation fully confirmed?");
        }

        String acdcAtc = new String(Utils.serializeACDCAttachment(args.getIss()));
        String issAtc = new String(Utils.serializeIssExnAttachment(args.getAnc()));
        String ancAtc = args.getAncAttachment();

        Map<String, List<Object>> embeds = new LinkedHashMap<>();
        embeds.put("acdc", Arrays.asList(args.getAcdc(), acdcAtc));
        embeds.put("iss", Arrays.asList(args.getIss(), issAtc));
        embeds.put("anc", Arrays.asList(args.getAnc(), ancAtc));

        Map<String, Object> data = Map.of(
                "m", args.getMessage() != null ? args.getMessage() : "",
                "a", Map.of("oobiUrl", schemaUrl),
                "s", schemaSAID
        );

        return client.exchanges().createExchangeMessage(
                hab, "/ipex/grant", data, embeds,
                args.getRecipient(), args.getDatetime(), args.getAgreeSaid());
    }

    /**
     * KERIA's /states?pre=X returns a LIST of key-state dicts. Some signify-java
     * versions / single-result paths return a bare map. Handle both.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceKeyState(Object raw) {
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof List) {
            List<Object> list = (List<Object>) raw;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                return (Map<String, Object>) list.get(0);
            }
        }
        return null;
    }
}
