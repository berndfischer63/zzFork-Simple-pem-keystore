package io.r2.simplepemkeystore;

import com.sun.net.httpserver.HttpsServer;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Key;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests if reloadable key store (certificate refresh)
 */
public class ReloadablePemKeyStoreIntegrationTest extends HttpsBaseFunctions {


    private Path certPath;
    private Path keyPath;


    @BeforeClass
    public void registerProvider() throws Exception {
        Security.addProvider(new SimplePemKeyStoreProvider());

        certPath = Files.createTempFile("test-cert", ".pem");
        keyPath = Files.createTempFile("test-key", ".pem");
    }

    @AfterClass
    public void deleteTempFiles() throws Exception {
        Files.delete(certPath);
        Files.delete(keyPath);
    }

    private void copyCertKey(String cert, String key) throws Exception {
        String prefix = "src/test/resources/";
        Files.copy(new File(prefix+cert).toPath(), certPath, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(new File(prefix+key).toPath(), keyPath, StandardCopyOption.REPLACE_EXISTING);
        certPath.toFile().setLastModified(System.currentTimeMillis());
        keyPath.toFile().setLastModified(System.currentTimeMillis());
    }

    private KeyStore getKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance("simplepemreload");

        ks.load(
                new ReloadablePemKeyStoreConfig()
                        .addCertificate("server", new String[] {
                                certPath.toFile().getCanonicalPath(),
                                keyPath.toFile().getCanonicalPath()
                        })
                        .withRefreshInterval(5)
                        .asInputStream(),
                new char[0] // no password
        );
        return ks;
    }


    @Test
    public void testHttps_simplepemreload() throws Exception {
        // skip long tests if io.r2.skipLongTests is set to true
        if ("true".equals(System.getProperty("io.r2.skipLongTests"))) throw new SkipException("Long test skipped");

        copyCertKey("certchain.pem", "key.pem");

        KeyStore ks = getKeyStore();

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("simplepemreload");
        kmf.init( ExpiringCacheKeyManagerParameters.forKeyStore(ks).withRevalidation(5) );

        KeyManager[] km = kmf.getKeyManagers();
        assertThat(km).hasSize(1);

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(km, null, null);

        HttpsServer server = startHttpsServer(ctx);

        try {
            HttpsURLConnection conn = createClientConnection();

            assertThat(conn.getPeerPrincipal().getName()).isEqualTo("CN=anna.apn2.com");

            Thread.sleep(1000); // avoid very quick overwriting of file in case of quick test run

            copyCertKey("selfcert.pem", "selfkey.pem");

            Thread.sleep(10000); // wait for picking up the change in 5 seconds (+extra)

            HttpsURLConnection conn2 = createClientConnection();

            assertThat(conn2.getPeerPrincipal().getName()).isEqualTo("CN=self.signed.cert,O=Radical Research,ST=NA,C=IO");

        }
        finally {
            // stop server
            server.stop(0);
        }
    }

    @Test
    public void testHttps_simplepem() throws Exception {
        // skip long tests if io.r2.skipLongTests is set to true
        if ("true".equals(System.getProperty("io.r2.skipLongTests"))) throw new SkipException("Long test skipped");

        KeyStore ks = KeyStore.getInstance("simplepem");
        ks.load(new MultiFileConcatSource()
                    .alias("server")
                    .add("src/test/resources/certchain.pem")
                    .add("src/test/resources/key.pem")
                    .build(),
                new char[0]
        );

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("simplepemreload");
        kmf.init( ExpiringCacheKeyManagerParameters.forKeyStore(ks).withRevalidation(5) );

        KeyManager[] km = kmf.getKeyManagers();
        assertThat(km).hasSize(1);

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(km, null, null);

        HttpsServer server = startHttpsServer(ctx);

        try {
            HttpsURLConnection conn = createClientConnection();

            assertThat(conn.getPeerPrincipal().getName()).isEqualTo("CN=anna.apn2.com");

            ks.load(new MultiFileConcatSource()
                            .alias("server")
                            .add("src/test/resources/selfcert.pem")
                            .add("src/test/resources/selfkey.pem")
                            .build(),
                    new char[0]
            );

            Thread.sleep(10000); // wait for picking up the change in 5 seconds (+extra)

            HttpsURLConnection conn2 = createClientConnection();

            assertThat(conn2.getPeerPrincipal().getName()).isEqualTo("CN=self.signed.cert,O=Radical Research,ST=NA,C=IO");

        }
        finally {
            // stop server
            server.stop(0);
        }
    }


}
