/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Util;
import hudson.diagnosis.OldDataMonitor;
import hudson.util.Secret;
import jenkins.util.SystemProperties;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ApiTokenStore {
    private static final Logger LOGGER = Logger.getLogger(OldDataMonitor.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    
    private static final Comparator<HashedToken> SORT_BY_LOWERCASED_NAME =
            Comparator.comparing(hashedToken -> hashedToken.getName().toLowerCase(Locale.ENGLISH));
    
    private static final int TOKEN_LENGTH_V2 = 34;
    /** two hex-characters */
    private static final String LEGACY_VERSION = "01";
    private static final String HASH_VERSION = "02";
    
    private static final String HASH_ALGORITHM = "SHA-256";
    
    private List<HashedToken> tokenList;
    
    public ApiTokenStore() {
        this.init();
    }
    
    public ApiTokenStore readResolve() {
        this.init();
        return this;
    }
    
    private void init() {
        if (this.tokenList == null) {
            this.tokenList = new ArrayList<>();
        }
    }
    
    @SuppressFBWarnings("NP_NONNULL_RETURN_VIOLATION")
    public synchronized @Nonnull Collection<HashedToken> getTokenListSortedByName() {
        return tokenList.stream()
                .sorted(SORT_BY_LOWERCASED_NAME)
                .collect(Collectors.toList());
    }
    
    private void addToken(HashedToken token) {
        this.tokenList.add(token);
    }
    
    /**
     * Defensive approach to avoid involuntary change since the UUIDs are generated at startup only for UI
     * and so between restart they change
     */
    public synchronized void reconfigure(@Nonnull Map<String, JSONObject> tokenStoreDataMap) {
        tokenList.forEach(hashedToken -> {
            JSONObject receivedTokenData = tokenStoreDataMap.get(hashedToken.uuid);
            if (receivedTokenData == null) {
                LOGGER.log(Level.INFO, "No token received for {0}", hashedToken.uuid);
                return;
            }
            
            String name = receivedTokenData.getString("tokenName");
            if (StringUtils.isBlank(name)) {
                LOGGER.log(Level.INFO, "Empty name received for {0}, we do not care about it", hashedToken.uuid);
                return;
            }
            
            hashedToken.setName(name);
        });
    }
    
    public synchronized void generateTokenFromLegacy(@Nonnull Secret newLegacyApiToken) {
        deleteAllLegacyTokens();
        addLegacyToken(newLegacyApiToken);
    }
    
    private void deleteAllLegacyTokens() {
        // normally there is only one, but just in case
        tokenList.removeIf(HashedToken::isLegacy);
    }
    
    private void addLegacyToken(@Nonnull Secret legacyToken) {
        String tokenUserUseNormally = Util.getDigestOf(legacyToken.getPlainText());
        
        String secretValueHashed = this.plainSecretToHashInHex(tokenUserUseNormally);
        
        HashValue hashValue = new HashValue(LEGACY_VERSION, secretValueHashed);
        HashedToken token = HashedToken.buildNew(Messages.ApiTokenProperty_LegacyTokenName(), hashValue);
        
        this.addToken(token);
    }
    
    public synchronized @Nonnull TokenIdAndPlainValue generateNewToken(@Nonnull String name) {
        // 16x8=128bit worth of randomness, using brute-force you need on average 2^127 tries (~10^37)
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String secretValue = Util.toHexString(random);
        String tokenTheUserWillUse = HASH_VERSION + secretValue;
        assert tokenTheUserWillUse.length() == 2 + 32;
        
        String secretValueHashed = this.plainSecretToHashInHex(secretValue);
        
        HashValue hashValue = new HashValue(HASH_VERSION, secretValueHashed);
        HashedToken token = HashedToken.buildNew(name, hashValue);
        
        this.addToken(token);
    
        return new TokenIdAndPlainValue(token.uuid, tokenTheUserWillUse);
    }
    
    @SuppressFBWarnings("NP_NONNULL_RETURN_VIOLATION")
    private @Nonnull String plainSecretToHashInHex(@Nonnull String secretValueInPlainText) {
        byte[] hashBytes = plainSecretToHashBytes(secretValueInPlainText);
        return Util.toHexString(hashBytes);
    }
    
    private @Nonnull byte[] plainSecretToHashBytes(@Nonnull String secretValueInPlainText) {
        // ascii is sufficient for hex-format
        return hashedBytes(secretValueInPlainText.getBytes(StandardCharsets.US_ASCII));
    }

    private @Nonnull byte[] hashedBytes(byte[] tokenBytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("There is no " + HASH_ALGORITHM + " available in this system");
        }
        return digest.digest(tokenBytes);
    }
    
    public synchronized boolean doesContainToken(@Nonnull String token) {
        String plainToken;
        if (isLegacyToken(token)) {
            plainToken = token;
        } else {
            plainToken = getHashOfToken(token);
        }
        
        return searchMatch(plainToken);
    }
    
    private boolean isLegacyToken(String token) {
        return token.length() != TOKEN_LENGTH_V2;
    }
    
    /**
     * [2: version][32: real token]
     * ^^^^^^^^^^^^----------------
     */
    private String getVersionOfToken(String token) {
        return token.substring(0, 1);
    }
    
    /**
     * [2: version][32: real token]
     * ------------^^^^^^^^^^^^^^^^
     */
    private String getHashOfToken(String token) {
        return token.substring(2);
    }
    
    private boolean searchMatch(String plainToken) {
        byte[] hashedBytes = plainSecretToHashBytes(plainToken);
        for (HashedToken token : tokenList) {
            if(token.match(hashedBytes)){
                token.incrementUse();
                return true;
            }
        }

        return false;
    }
    
    public synchronized @CheckForNull HashedToken revokeToken(@Nonnull String tokenUuid) {
        for (Iterator<HashedToken> iterator = tokenList.iterator(); iterator.hasNext();) {
            HashedToken token = iterator.next();
            if (token.uuid.equals(tokenUuid)) {
                iterator.remove();

                return token;
            }
        }

        return null;
    }
    
    public synchronized boolean renameToken(@Nonnull String tokenUuid, @Nonnull String newName) {
        for (HashedToken token : tokenList) {
            if (token.uuid.equals(tokenUuid)) {
                token.rename(newName);
                return true;
            }
        }

        LOGGER.log(Level.FINER, "The target token for rename does not exist, for uuid = {0}, with desired name = {1}", new Object[]{tokenUuid, newName});
        return false;
    }
    
    /**
     * [2: version][32: real token]
     */
    @Immutable
    private static class HashValue {
        /** To ease future implementation */
        private final String version;
        /** The only confidential information. The token is stored as a SHA-256 hash, in hex format */
        private final String hash;
        
        public HashValue(String version, String hash) {
            this.version = version;
            this.hash = hash;
        }
    }
    
    @Immutable
    public static class TokenIdAndPlainValue {
        public final String tokenId;
        public final String plainValue;
    
        public TokenIdAndPlainValue(String tokenId, String plainValue) {
            this.tokenId = tokenId;
            this.plainValue = plainValue;
        }
    }
    
    public static class HashedToken {
        // to ease the modification of the token through the UI
        private transient String uuid;
        private String name;
        private Date creationDate;
        
        private HashValue value;
        
        private Date lastUseDate;
        private Integer useCounter;
        
        public HashedToken() {
            this.init();
        }
        
        public HashedToken readResolve() {
            this.init();
            return this;
        }
        
        private void init() {
            this.uuid = UUID.randomUUID().toString();
        }
        
        public static @Nonnull HashedToken buildNew(@Nonnull String name, @Nonnull HashValue value) {
            HashedToken result = new HashedToken();
            result.name = name;
            result.creationDate = new Date();
            
            result.value = value;
            
            return result;
        }
        
        public void rename(String newName) {
            this.name = newName;
        }
        
        public boolean match(byte[] hashedBytes) {
            byte[] hashFromHex;
            try{
                hashFromHex = Util.fromHexString(value.hash);
            }
            catch(NumberFormatException e){
                LOGGER.log(Level.INFO, "The API token with name=[{0}] is not in hex-format and so cannot be used", name);
                return false;
            }

            // String.equals() is not constant-time but this method is. No link between correctness and time spent
            return MessageDigest.isEqual(hashFromHex, hashedBytes);
        }
        
        public String getName() {
            return name;
        }
        
        public int getUseCounter() {
            return useCounter == null ? 0 : useCounter;
        }
        
        public long getNumDaysUse() {
            return lastUseDate == null ? 0 : computeDeltaDays(lastUseDate.toInstant(), Instant.now());
        }
        
        public long getNumDaysCreation() {
            // should not happen but just in case
            return creationDate == null ? 0 : computeDeltaDays(creationDate.toInstant(), Instant.now());
        }
        
        public String getUuid() {
            return this.uuid;
        }
        
        private long computeDeltaDays(Instant a, Instant b) {
            long deltaDays = ChronoUnit.DAYS.between(a, b);
            deltaDays = Math.max(0, deltaDays);
            return deltaDays;
        }
        
        public boolean isLegacy() {
            return this.value.version.equals(LEGACY_VERSION);
        }
        
        public void incrementUse() {
            this.useCounter = useCounter == null ? 1 : useCounter + 1;
            this.lastUseDate = new Date();
        }
        
        public void setName(String name) {
            this.name = name;
        }
    }
}
