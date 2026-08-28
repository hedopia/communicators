package com.sds.communicators.driver;

import com.google.common.net.InetAddresses;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;

// Persistent OPC UA application identity and file-backed PKI trust store.
final class OpcuaSecurityStore implements AutoCloseable {
    static final String PASSWORD_PROPERTY = "communicators.opcua.key-store-password";
    static final String PASSWORD_ENV = "COMMUNICATORS_OPCUA_KEY_STORE_PASSWORD";

    private final char[] password;
    private final FileBasedTrustListManager trustListManager;
    private final KeyStoreCertificateStore certificateStore;
    private final CertificateValidator certificateValidator;
    private final CertificateManager certificateManager;
    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final X509Certificate[] certificateChain;

    static OpcuaSecurityStore openClient(
            Path pkiDir,
            String configuredPassword,
            String applicationUri,
            String commonName) throws Exception {
        return open(pkiDir, configuredPassword, applicationUri, commonName, java.util.List.of(), false);
    }

    static OpcuaSecurityStore openServer(
            Path pkiDir,
            String configuredPassword,
            String applicationUri,
            String commonName,
            Collection<String> hostnames) throws Exception {
        return open(pkiDir, configuredPassword, applicationUri, commonName, hostnames, true);
    }

    private static OpcuaSecurityStore open(
            Path pkiDir,
            String configuredPassword,
            String applicationUri,
            String commonName,
            Collection<String> hostnames,
            boolean server) throws Exception {
        Path root = pkiDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        char[] password = resolvePassword(root, configuredPassword);

        FileBasedTrustListManager trustListManager = null;
        KeyStoreCertificateStore certificateStore = null;
        try {
            trustListManager = FileBasedTrustListManager.createAndInitialize(root.resolve("trust"));
            var quarantine = FileBasedCertificateQuarantine.create(root.resolve("rejected"));
            CertificateValidator validator = server
                    ? new DefaultServerCertificateValidator(trustListManager, quarantine)
                    : new DefaultClientCertificateValidator(trustListManager, quarantine);

            var settings = new KeyStoreCertificateStore.Settings(
                    root.resolve("identity.pfx"),
                    () -> password.clone(),
                    alias -> password.clone(),
                    false);
            certificateStore = KeyStoreCertificateStore.createAndInitialize(settings);

            var certificateFactory = new RsaSha256CertificateFactory() {
                @Override
                protected X509Certificate[] createRsaSha256CertificateChain(KeyPair keyPair) throws Exception {
                    var builder = new SelfSignedCertificateBuilder(keyPair)
                            .setCommonName(commonName)
                            .setOrganization("SDS")
                            .setApplicationUri(applicationUri);
                    for (String hostname : hostnames) {
                        if (InetAddresses.isInetAddress(hostname))
                            builder.addIpAddress(hostname);
                        else
                            builder.addDnsName(hostname);
                    }
                    return new X509Certificate[]{builder.build()};
                }
            };

            var applicationGroup = DefaultApplicationGroup.createAndInitialize(
                    trustListManager,
                    certificateStore,
                    certificateFactory,
                    validator);
            var certificateEntry = applicationGroup.getCertificateEntries().stream()
                    .findFirst()
                    .orElseThrow(() -> new Exception("OPC UA application certificate was not initialized"));
            var keyPair = applicationGroup.getKeyPair(certificateEntry.certificateTypeId)
                    .orElseThrow(() -> new Exception("OPC UA application key pair was not initialized"));
            if (certificateEntry.certificateChain.length == 0)
                throw new Exception("OPC UA application certificate chain is empty");

            return new OpcuaSecurityStore(
                    password,
                    trustListManager,
                    certificateStore,
                    validator,
                    new DefaultCertificateManager(quarantine, applicationGroup),
                    keyPair,
                    certificateEntry.certificateChain[0],
                    certificateEntry.certificateChain);
        } catch (Exception e) {
            closeQuietly(certificateStore);
            closeQuietly(trustListManager);
            Arrays.fill(password, '\0');
            throw e;
        }
    }

    private OpcuaSecurityStore(
            char[] password,
            FileBasedTrustListManager trustListManager,
            KeyStoreCertificateStore certificateStore,
            CertificateValidator certificateValidator,
            CertificateManager certificateManager,
            KeyPair keyPair,
            X509Certificate certificate,
            X509Certificate[] certificateChain) {
        this.password = password;
        this.trustListManager = trustListManager;
        this.certificateStore = certificateStore;
        this.certificateValidator = certificateValidator;
        this.certificateManager = certificateManager;
        this.keyPair = keyPair;
        this.certificate = certificate;
        this.certificateChain = certificateChain.clone();
    }

    private static char[] resolvePassword(Path root, String configuredPassword) throws IOException {
        if (configuredPassword != null && !configuredPassword.isBlank())
            return configuredPassword.toCharArray();

        String propertyPassword = System.getProperty(PASSWORD_PROPERTY);
        if (propertyPassword != null && !propertyPassword.isBlank())
            return propertyPassword.toCharArray();

        String environmentPassword = System.getenv(PASSWORD_ENV);
        if (environmentPassword != null && !environmentPassword.isBlank())
            return environmentPassword.toCharArray();

        Path passwordFile = root.resolve("identity.password");
        if (!Files.exists(passwordFile)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Arrays.fill(random, (byte) 0);
            try {
                Files.writeString(
                        passwordFile,
                        generated,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                restrictOwnerOnly(passwordFile);
            } catch (FileAlreadyExistsException ignored) {
                // Another connection created the shared password file first.
            }
        }

        String password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim();
        if (password.isEmpty())
            throw new IOException("OPC UA identity password file is empty: " + passwordFile);
        return password.toCharArray();
    }

    private static void restrictOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and non-POSIX file systems use inherited ACLs.
        }
    }

    CertificateValidator certificateValidator() {
        return certificateValidator;
    }

    CertificateManager certificateManager() {
        return certificateManager;
    }

    KeyPair keyPair() {
        return keyPair;
    }

    X509Certificate certificate() {
        return certificate;
    }

    X509Certificate[] certificateChain() {
        return certificateChain.clone();
    }

    @Override
    public void close() {
        closeQuietly(certificateStore);
        closeQuietly(trustListManager);
        Arrays.fill(password, '\0');
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}