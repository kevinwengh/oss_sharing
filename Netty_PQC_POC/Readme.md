
## Build
```shell
mvn clean install -DskipTests -Dspotbugs.skip -Dcheckstyle.skip -Dpmd.skip
```

## Run
```bash
./run.sh
```

## Verify
Then verify with OpenSSL:

```shell
./verify.sh
```

Expected output from API:

```shell
curl https://localhost:8443/crypto/pqc -k

# should include:
# Configured TLS Groups:
# SecP256r1MLKEM768,X25519MLKEM768,secp256r1
#
# ML-KEM Hybrid Enabled: YES
```
