/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.authentication.manager;

import org.cloudfoundry.identity.uaa.authentication.AccountNotVerifiedException;
import org.cloudfoundry.identity.uaa.authentication.AuthenticationPolicyRejectionException;
import org.cloudfoundry.identity.uaa.authentication.AuthzAuthenticationRequest;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.authentication.PasswordExpiredException;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthenticationDetails;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.authentication.event.UnverifiedUserAuthenticationEvent;
import org.cloudfoundry.identity.uaa.authentication.event.UserAuthenticationFailureEvent;
import org.cloudfoundry.identity.uaa.authentication.event.UserAuthenticationSuccessEvent;
import org.cloudfoundry.identity.uaa.authentication.event.UserNotFoundEvent;
import org.cloudfoundry.identity.uaa.config.PasswordPolicy;
import org.cloudfoundry.identity.uaa.user.UaaAuthority;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.user.UaaUserDatabase;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityProvider;
import org.cloudfoundry.identity.uaa.zone.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.UaaIdentityProviderDefinition;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;

import javax.servlet.http.HttpServletRequest;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Luke Taylor
 */
public class AuthzAuthenticationManagerTests {
    private AuthzAuthenticationManager mgr;
    private UaaUserDatabase db;
    private ApplicationEventPublisher publisher;
    private static final String PASSWORD = "$2a$10$HoWPAUn9zqmmb0b.2TBZWe6cjQcxyo8TDwTX.5G46PBL347N3/0zO"; // "password"
    private UaaUser user = null;
    private BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private String loginServerUserName="loginServerUser".toLowerCase();
    private IdentityProviderProvisioning providerProvisioning;

    @Before
    public void setUp() throws Exception {
        String id = new RandomValueStringGenerator().generate();
        user = new UaaUser(
            id,
            "auser",
            PASSWORD,
            "auser@blah.com",
            UaaAuthority.USER_AUTHORITIES,
            "A", "User",
            new Date(),
            new Date(),
            Origin.UAA,
            null,
            true,
            IdentityZoneHolder.get().getId(),
            id,
            new Date());
        providerProvisioning = mock(IdentityProviderProvisioning.class);
        db = mock(UaaUserDatabase.class);
        publisher = mock(ApplicationEventPublisher.class);
        mgr = new AuthzAuthenticationManager(db, encoder, providerProvisioning);
        mgr.setApplicationEventPublisher(publisher);
        mgr.setOrigin(Origin.UAA);
    }

    @Test
    public void successfulAuthentication() throws Exception {
        when(db.retrieveUserByName("auser", Origin.UAA)).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));
        assertNotNull(result);
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());
    }

    @Test(expected = PasswordExpiredException.class)
    public void unsuccessfulPasswordExpired() throws Exception {
        IdentityProvider provider = new IdentityProvider();

        UaaIdentityProviderDefinition idpDefinition = new UaaIdentityProviderDefinition(new PasswordPolicy(6,128,1,1,1,1,6), null);
        provider.setConfig(JsonUtils.writeValueAsString(idpDefinition));

        when(providerProvisioning.retrieveByOrigin(anyString(), anyString())).thenReturn(provider);

        Calendar oneYearAgoCal = Calendar.getInstance();
        oneYearAgoCal.add(Calendar.YEAR, -1);
        Date oneYearAgo = new Date(oneYearAgoCal.getTimeInMillis());
        user = new UaaUser(
            user.getId(),
            user.getUsername(),
            PASSWORD,
            user.getPassword(),
            user.getAuthorities(),
            user.getGivenName(),
            user.getFamilyName(),
            oneYearAgo,
            oneYearAgo,
            Origin.UAA,
            null,
            true,
            IdentityZoneHolder.get().getId(),
            user.getSalt(),
            oneYearAgo);
        when(db.retrieveUserByName("auser", Origin.UAA)).thenReturn(user);
        mgr.authenticate(createAuthRequest("auser", "password"));
    }

    @Test(expected = BadCredentialsException.class)
    public void unsuccessfulLoginServerUserAuthentication() throws Exception {
        when(db.retrieveUserByName(loginServerUserName,Origin.UAA)).thenReturn(null);
        mgr.authenticate(createAuthRequest(loginServerUserName, ""));
    }

    @Test(expected = BadCredentialsException.class)
    public void unsuccessfulLoginServerUserWithPasswordAuthentication() throws Exception {
        when(db.retrieveUserByName(loginServerUserName,Origin.UAA)).thenReturn(null);
        mgr.authenticate(createAuthRequest(loginServerUserName, "dadas"));
    }

    @Test
    public void successfulAuthenticationReturnsTokenAndPublishesEvent() throws Exception {
        when(db.retrieveUserByName("auser",Origin.UAA)).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));

        assertNotNull(result);
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());

        verify(publisher).publishEvent(isA(UserAuthenticationSuccessEvent.class));
    }

    @Test
    public void invalidPasswordPublishesAuthenticationFailureEvent() {
        when(db.retrieveUserByName("auser",Origin.UAA)).thenReturn(user);
        try {
            mgr.authenticate(createAuthRequest("auser", "wrongpassword"));
            fail();
        } catch (BadCredentialsException expected) {
        }

        verify(publisher).publishEvent(isA(UserAuthenticationFailureEvent.class));
    }

    @Test(expected = AuthenticationPolicyRejectionException.class)
    public void authenticationIsDeniedIfRejectedByLoginPolicy() throws Exception {
        when(db.retrieveUserByName("auser", Origin.UAA)).thenReturn(user);
        AccountLoginPolicy lp = mock(AccountLoginPolicy.class);
        when(lp.isAllowed(any(UaaUser.class), any(Authentication.class))).thenReturn(false);
        mgr.setAccountLoginPolicy(lp);
        mgr.authenticate(createAuthRequest("auser", "password"));
    }

    @Test
    public void missingUserPublishesNotFoundEvent() {
        when(db.retrieveUserByName(eq("aguess"),eq(Origin.UAA))).thenThrow(new UsernameNotFoundException("mocked"));
        try {
            mgr.authenticate(createAuthRequest("aguess", "password"));
            fail();
        } catch (BadCredentialsException expected) {
        }

        verify(publisher).publishEvent(isA(UserNotFoundEvent.class));
    }

    @Test
    public void successfulVerifyOriginAuthentication1() throws Exception {
        mgr.setOrigin("test");
        user = user.modifySource("test",null);
        when(db.retrieveUserByName("auser","test")).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));
        assertNotNull(result);
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());
    }

    @Test(expected = BadCredentialsException.class)
    public void originAuthenticationFail() throws Exception {
        when(db.retrieveUserByName("auser", "not UAA")).thenReturn(user);
        mgr.authenticate(createAuthRequest("auser", "password"));
    }

    @Test
    public void unverifiedAuthenticationSucceedsWhenAllowed() throws Exception {
        user.setVerified(false);
        when(db.retrieveUserByName("auser", Origin.UAA)).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());
        }

    @Test
    public void unverifiedAuthenticationFailsWhenNotAllowed() throws Exception {
        mgr.setAllowUnverifiedUsers(false);
        user.setVerified(false);
        when(db.retrieveUserByName("auser", Origin.UAA)).thenReturn(user);
        try {
            mgr.authenticate(createAuthRequest("auser", "password"));

            fail("Expected AccountNotVerifiedException");
        } catch (AccountNotVerifiedException e) {
            // woo hoo
        }
        verify(publisher).publishEvent(isA(UnverifiedUserAuthenticationEvent.class));
    }

    @Test
    public void unverified_authentication_succeeds_in_non_default_zone_even_when_not_allowed() throws Exception {
        IdentityZone calZone = new IdentityZone();
        calZone.setId("cal-zone");
        IdentityZoneHolder.set(calZone);
        mgr.setAllowUnverifiedUsers(false);

        Date justASecondAgo = new Date(System.currentTimeMillis() - 1000);
        UaaUser calZoneUser = new UaaUser(
            user.getId(),
            user.getUsername(),
            PASSWORD,
            user.getPassword(),
            user.getAuthorities(),
            user.getGivenName(),
            user.getFamilyName(),
            justASecondAgo,
            justASecondAgo,
            Origin.UAA,
            null,
            true,
            IdentityZoneHolder.get().getId(),
            user.getSalt(),
            justASecondAgo);

        calZoneUser.setVerified(false);
        when(db.retrieveUserByName("auser", Origin.UAA)).thenReturn(calZoneUser);
        try {
            mgr.authenticate(createAuthRequest("auser", "password"));
        } catch (AccountNotVerifiedException e) {
            fail("Did not expect AccountNotVerifiedException. User should've been allowed to authenticate.");
        }
        verify(publisher).publishEvent(isA(UserAuthenticationSuccessEvent.class));
        IdentityZoneHolder.clear();
    }

    @Test
    public void userIsLockedOutAfterNumberOfFailedTriesIsExceeded() throws Exception {
        AccountLoginPolicy lockoutPolicy = mock(PeriodLockoutPolicy.class);
        mgr.setAccountLoginPolicy(lockoutPolicy);
        when(db.retrieveUserByName("auser", Origin.UAA)).thenReturn(user);
        Authentication authentication = createAuthRequest("auser", "password");
        when(lockoutPolicy.isAllowed(any(UaaUser.class), eq(authentication))).thenReturn(false);

        try {
            mgr.authenticate(authentication);
        } catch (AuthenticationPolicyRejectionException e) {
            // woo hoo
        }

        assertFalse(authentication.isAuthenticated());
        verify(publisher).publishEvent(isA(AuthenticationFailureLockedEvent.class));
    }

    AuthzAuthenticationRequest createAuthRequest(String username, String password) {
        Map<String, String> userdata = new HashMap<String, String>();
        userdata.put("username", username);
        userdata.put("password", password);
        return new AuthzAuthenticationRequest(userdata, new UaaAuthenticationDetails(mock(HttpServletRequest.class)));
    }
}
