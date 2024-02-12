/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/

package org.cloudfoundry.identity.uaa.scim.endpoints;

import static java.util.Collections.singletonList;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toList;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.cloudfoundry.identity.uaa.resources.SearchResults;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.exception.ScimException;
import org.cloudfoundry.identity.uaa.security.beans.SecurityContextAccessor;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * @author Dave Syer
 * @author Luke Taylor
 */
public class UserIdConversionEndpointsTests {

    @Rule
    public ExpectedException expected = ExpectedException.none();

    private UserIdConversionEndpoints endpoints;

    private SecurityContextAccessor mockSecurityContextAccessor;

    private final ScimUserEndpoints scimUserEndpoints = mock(ScimUserEndpoints.class);

    private final ScimUserProvisioning scimUserProvisioning = mock(ScimUserProvisioning.class);

    private final MockedStatic<IdentityZoneHolder> idzHolderMockedStatic;
    private final IdentityZoneManager identityZoneManager = mock(IdentityZoneManager.class);

    @SuppressWarnings("rawtypes")
    private final Collection authorities = AuthorityUtils
            .commaSeparatedStringToAuthorityList("orgs.foo,uaa.user");

    public UserIdConversionEndpointsTests() {
        this.idzHolderMockedStatic = mockStatic(IdentityZoneHolder.class);
    }

    @SuppressWarnings("unchecked")
    @Before
    public void init() {
        mockSecurityContextAccessor = mock(SecurityContextAccessor.class);
        endpoints = new UserIdConversionEndpoints(mockSecurityContextAccessor, scimUserEndpoints, scimUserProvisioning, identityZoneManager, true);
        when(mockSecurityContextAccessor.getAuthorities()).thenReturn(authorities);
        when(mockSecurityContextAccessor.getAuthenticationInfo()).thenReturn("mock object");
        when(scimUserEndpoints.getUserMaxCount()).thenReturn(10_000);
    }

    @After
    public void tearDown() throws Exception {
        idzHolderMockedStatic.close();
    }

    @Test
    public void testHappyDay() {
        arrangeCurrentIdentityZone("uaa");
        endpoints.findUsers("userName eq \"marissa\"", "ascending", 0, 100, false);
    }

    @Test
    public void testSanitizeExceptionInFilter() {
        expected.expect(ScimException.class);
        expected.expectMessage(is("Invalid filter '&lt;svg onload=alert(document.domain)&gt;'"));
        endpoints.findUsers("<svg onload=alert(document.domain)>", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFieldInFilter() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid filter"));
        endpoints.findUsers("emails.value eq \"foo@bar.org\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilterWithGroup() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid filter"));
        endpoints.findUsers("groups.display eq \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testGoodFilter_IncludeInactive() {
        final String idzId = randomUUID().toString();
        arrangeCurrentIdentityZone(idzId);

        final String filter = "(username eq \"foo\" and id eq \"bar\") or username eq \"bar\"";

        final List<ScimUser> allScimUsers = new ArrayList<>();
        for (int i = 0; i < 5; ++i) {
            final ScimUser scimUser = new ScimUser(randomUUID().toString(), "bar", "Some", "Name");
            scimUser.setOrigin("idp2");
            allScimUsers.add(scimUser);
        }
        final ScimUser scimUser6 = new ScimUser("bar", "foo", "Some", "Name");
        scimUser6.setOrigin("idp1");
        allScimUsers.add(scimUser6);
        assertThat(allScimUsers).hasSize(6);
        arrangeScimUsersForFilter(filter, allScimUsers, true, idzId);

        // check different page sizes -> should return all users, since 'includeInactive' is true
        assertEndpointReturnsCorrectResult(filter, 1, allScimUsers, true);
        assertEndpointReturnsCorrectResult(filter, 2, allScimUsers, true);
        assertEndpointReturnsCorrectResult(filter, 3, allScimUsers, true);
        assertEndpointReturnsCorrectResult(filter, 4, allScimUsers, true);
        assertEndpointReturnsCorrectResult(filter, 10, allScimUsers, true);
    }

    @Test
    public void testGoodFilter_OnlyActive() {
        final String idzId = randomUUID().toString();
        arrangeCurrentIdentityZone(idzId);

        final String filter = "(username eq \"foo\" and id eq \"bar\") or username eq \"bar\"";

        // one active user
        final ScimUser scimUser = new ScimUser("bar", "foo", "Some", "Name");
        scimUser.setOrigin("idp1");
        final List<ScimUser> expectedUsers = singletonList(scimUser);
        arrangeScimUsersForFilter(filter, expectedUsers, false, idzId);

        // check different page sizes
        assertEndpointReturnsCorrectResult(filter, 1, expectedUsers, false);
        assertEndpointReturnsCorrectResult(filter, 2, expectedUsers, false);
        assertEndpointReturnsCorrectResult(filter, 3, expectedUsers, false);
        assertEndpointReturnsCorrectResult(filter, 4, expectedUsers, false);
        assertEndpointReturnsCorrectResult(filter, 10, expectedUsers, false);
    }

    @Test
    public void testGoodFilter1() {
        testGoodFilter("(id eq \"foo\" or username eq \"bar\") and origin eq \"uaa\"");
    }

    @Test
    public void testGoodFilter2() {
        testGoodFilter("origin eq \"uaa\" and (id eq \"foo\" or username eq \"bar\")");
    }

    @Test
    public void testGoodFilter3() {
        testGoodFilter("(id eq \"foo\" and username eq \"bar\") or id eq \"bar\"");
    }

    @Test
    public void testGoodFilter4() {
        testGoodFilter("id eq \"bar\" and (id eq \"foo\" and username eq \"bar\")");
    }

    private void testGoodFilter(final String filter) {
        arrangeCurrentIdentityZone("uaa");
        final ResponseEntity<Object> response = endpoints.findUsers(
                filter,
                "ascending",
                0,
                100,
                false
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testBadFilter1() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Wildcards are not allowed in filter."));
        endpoints.findUsers("id co \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter2() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid filter"));
        endpoints.findUsers("id sq \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter3() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Wildcards are not allowed in filter."));
        endpoints.findUsers("id sw \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter4() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Wildcards are not allowed in filter."));
        endpoints.findUsers("id pr", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter5() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid operator."));
        endpoints.findUsers("id gt \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter6() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid operator."));
        endpoints.findUsers("id gt \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter7() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid operator."));
        endpoints.findUsers("id lt \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter8() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid operator."));
        endpoints.findUsers("id le \"foo\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter9() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid filter"));
        endpoints.findUsers("origin eq \"uaa\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter10() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Wildcards are not allowed in filter."));

        // illegal operator in right operand of root-level "or" -> all branches need to be checked even if left operands are valid
        endpoints.findUsers("id eq \"foo\" or origin co \"uaa\"", "ascending", 0, 100, false);
    }

    @Test
    public void testBadFilter11() {
        expected.expect(ScimException.class);
        expected.expectMessage(containsString("Invalid filter"));
        endpoints.findUsers("origin eq \"uaa\" or origin eq \"bar\"", "ascending", 0, 100, false);
    }

    @Test
    public void testDisabled() {
        arrangeCurrentIdentityZone("uaa");
        endpoints = new UserIdConversionEndpoints(mockSecurityContextAccessor, scimUserEndpoints, scimUserProvisioning, identityZoneManager, false);
        ResponseEntity<Object> response = endpoints.findUsers("id eq \"foo\"", "ascending", 0, 100, false);
        Assert.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assert.assertEquals("Illegal Operation: Endpoint not enabled.", response.getBody());
    }

    @Test
    public void noActiveIdps_ReturnsEmptyResources() {
        arrangeCurrentIdentityZone("uaa");
        SearchResults<?> searchResults = (SearchResults<?>) endpoints.findUsers("username eq \"foo\"", "ascending", 0, 100, false).getBody();
        assertTrue(searchResults.getResources().isEmpty());
    }

    private void arrangeCurrentIdentityZone(final String idzId) {
        final IdentityZone identityZone = new IdentityZone();
        identityZone.setId(idzId);
        idzHolderMockedStatic.when(IdentityZoneHolder::get).thenReturn(identityZone);
        idzHolderMockedStatic.when(IdentityZoneHolder::getCurrentZoneId).thenReturn(idzId);
        when(identityZoneManager.getCurrentIdentityZoneId()).thenReturn(idzId);
    }

    private void arrangeScimUsersForFilter(
            final String filter,
            final List<ScimUser> allScimUsers,
            final boolean includeInactive,
            final String zoneId
    ) {
        when(scimUserProvisioning.retrieveByScimFilter(filter, includeInactive, "userName", true, zoneId))
                .thenReturn(allScimUsers);
    }

    private void assertEndpointReturnsCorrectResult(
            final String filter,
            final int resultsPerPage,
            final List<ScimUser> expectedUsers,
            final boolean includeInactive
    ) {
        final boolean lastPageIncomplete = expectedUsers.size() % resultsPerPage != 0;
        final int expectedPages = expectedUsers.size() / resultsPerPage + (lastPageIncomplete ? 1 : 0);

        final Function<Integer, ResponseEntity<Object>> fetchNextPage = (startIndex) -> endpoints.findUsers(
                filter, "ascending", startIndex, resultsPerPage, includeInactive
        );

        // collect all users in several pages
        final List<Map<String, Object>> observedUsers = new ArrayList<>();
        int currentStartIndex = 1;
        for (int i = 0; i < expectedPages; i++) {
            final ResponseEntity<Object> response = fetchNextPage.apply(currentStartIndex);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull().isInstanceOf(SearchResults.class);
            final SearchResults<Map<String, Object>> responseBody = (SearchResults<Map<String, Object>>) response.getBody();
            assertThat(responseBody.getTotalResults()).isEqualTo(expectedUsers.size());

            final int expectedNumberOfResultsInPage;
            if (i == expectedPages - 1 && lastPageIncomplete) {
                // last page -> might contain less elements
                expectedNumberOfResultsInPage = expectedUsers.size() % resultsPerPage;
            } else {
                // complete page
                expectedNumberOfResultsInPage = resultsPerPage;
            }
            assertThat(responseBody.getResources()).hasSize(expectedNumberOfResultsInPage);

            observedUsers.addAll(responseBody.getResources());
            currentStartIndex += responseBody.getResources().size();
        }

        // check next page -> should be empty
        final ResponseEntity<Object> response = fetchNextPage.apply(currentStartIndex);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isNotNull().isInstanceOf(SearchResults.class);
        final SearchResults<Map<String, Object>> responseBody = (SearchResults<Map<String, Object>>) response.getBody();
        assertThat(responseBody.getTotalResults()).isEqualTo(expectedUsers.size());
        assertThat(responseBody.getResources()).isNotNull().isEmpty();

        final List<Map<String, Object>> expectedResponse = expectedUsers.stream().map(scimUser -> Map.of(
                "id", (Object) scimUser.getId(),
                "userName", scimUser.getUserName(),
                "origin", scimUser.getOrigin()
        )).collect(toList());

        assertThat(observedUsers).hasSameElementsAs(expectedResponse);
    }
}
