/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.controllers;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
import io.dropwizard.auth.Auth;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.glassfish.jersey.server.ContainerRequest;
import org.whispersystems.textsecuregcm.auth.LinkedDeviceRefreshRequirementProvider;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.auth.BasicAuthorizationHeader;
import org.whispersystems.textsecuregcm.auth.ChangesLinkedDevices;
import org.whispersystems.textsecuregcm.entities.AccountAttributes;
import org.whispersystems.textsecuregcm.entities.DeviceActivationRequest;
import org.whispersystems.textsecuregcm.entities.DeviceInfo;
import org.whispersystems.textsecuregcm.entities.DeviceInfoList;
import org.whispersystems.textsecuregcm.entities.LinkDeviceResponse;
import org.whispersystems.textsecuregcm.entities.LinkDeviceRequest;
import org.whispersystems.textsecuregcm.entities.PreKeySignatureValidator;
import org.whispersystems.textsecuregcm.entities.ProvisioningMessage;
import org.whispersystems.textsecuregcm.entities.SetPublicKeyRequest;
import org.whispersystems.textsecuregcm.identity.IdentityType;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.metrics.MetricsUtil;
import org.whispersystems.textsecuregcm.metrics.UserAgentTagUtil;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.ClientPublicKeysManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.Device.DeviceCapabilities;
import org.whispersystems.textsecuregcm.storage.DeviceSpec;
import org.whispersystems.textsecuregcm.storage.LinkDeviceTokenAlreadyUsedException;
import org.whispersystems.textsecuregcm.util.EnumMapUtil;
import org.whispersystems.textsecuregcm.util.ExceptionUtils;
import org.whispersystems.textsecuregcm.util.LinkDeviceToken;
import org.whispersystems.textsecuregcm.util.ua.ClientPlatform;
import org.whispersystems.textsecuregcm.util.ua.UnrecognizedUserAgentException;
import org.whispersystems.textsecuregcm.util.ua.UserAgentUtil;
import org.whispersystems.websocket.auth.Mutable;
import org.whispersystems.websocket.auth.ReadOnly;

@Path("/v1/devices")
@Tag(name = "Devices")
public class DeviceController {

  static final int MAX_DEVICES = 6;

  private final AccountsManager accounts;
  private final ClientPublicKeysManager clientPublicKeysManager;
  private final RateLimiters rateLimiters;
  private final Map<String, Integer> maxDeviceConfiguration;

  private final EnumMap<ClientPlatform, AtomicInteger> linkedDeviceListenersByPlatform;
  private final AtomicInteger linkedDeviceListenersForUnrecognizedPlatforms;

  private static final String LINKED_DEVICE_LISTENER_GAUGE_NAME =
      MetricsUtil.name(DeviceController.class, "linkedDeviceListeners");

  private static final String WAIT_FOR_LINKED_DEVICE_TIMER_NAME =
      MetricsUtil.name(DeviceController.class, "waitForLinkedDeviceDuration");

  @VisibleForTesting
  static final int MIN_TOKEN_IDENTIFIER_LENGTH = 32;

  @VisibleForTesting
  static final int MAX_TOKEN_IDENTIFIER_LENGTH = 64;

  public DeviceController(final AccountsManager accounts,
      final ClientPublicKeysManager clientPublicKeysManager,
      final RateLimiters rateLimiters,
      final Map<String, Integer> maxDeviceConfiguration) {

    this.accounts = accounts;
    this.clientPublicKeysManager = clientPublicKeysManager;
    this.rateLimiters = rateLimiters;
    this.maxDeviceConfiguration = maxDeviceConfiguration;

    linkedDeviceListenersByPlatform =
        EnumMapUtil.toEnumMap(ClientPlatform.class, clientPlatform -> buildGauge(clientPlatform.name().toLowerCase()));

    linkedDeviceListenersForUnrecognizedPlatforms = buildGauge("unknown");
  }

  private static AtomicInteger buildGauge(final String clientPlatformName) {
    return Metrics.gauge(LINKED_DEVICE_LISTENER_GAUGE_NAME,
        Tags.of(io.micrometer.core.instrument.Tag.of(UserAgentTagUtil.PLATFORM_TAG, clientPlatformName)),
        new AtomicInteger(0));
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public DeviceInfoList getDevices(@ReadOnly @Auth AuthenticatedDevice auth) {
    return new DeviceInfoList(auth.getAccount().getDevices().stream()
        .map(DeviceInfo::forDevice)
        .toList());
  }

  @DELETE
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/{device_id}")
  @ChangesLinkedDevices
  public void removeDevice(@Mutable @Auth AuthenticatedDevice auth, @PathParam("device_id") byte deviceId) {
    if (auth.getAuthenticatedDevice().getId() != Device.PRIMARY_ID &&
        auth.getAuthenticatedDevice().getId() != deviceId) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    if (deviceId == Device.PRIMARY_ID) {
      throw new ForbiddenException();
    }

    accounts.removeDevice(auth.getAccount(), deviceId).join();
  }

  /**
   * Generates a signed device-linking token. Generally, primary devices will include the signed device-linking token in
   * a provisioning message to a new device, and then the new device will include the token in its request to
   * {@link #linkDevice(BasicAuthorizationHeader, String, LinkDeviceRequest, ContainerRequest)}.
   *
   * @param auth the authenticated account/device
   *
   * @return a signed device-linking token
   *
   * @throws RateLimitExceededException if the caller has made too many calls to this method in a set amount of time
   * @throws DeviceLimitExceededException if the authenticated account has already reached the maximum number of linked
   * devices
   *
   * @see ProvisioningController#sendProvisioningMessage(AuthenticatedDevice, String, ProvisioningMessage, String)
   */
  @GET
  @Path("/provisioning/code")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Generate a signed device-linking token",
      description = """
          Generate a signed device-linking token for transmission to a pending linked device via a provisioning message.
          """)
  @ApiResponse(responseCode="200", description="Token was generated successfully", useReturnTypeSchema=true)
  @ApiResponse(responseCode = "411", description = "The authenticated account already has the maximum allowed number of linked devices")
  @ApiResponse(responseCode = "429", description = "Too many attempts", headers = @Header(
      name = "Retry-After",
      description = "If present, an positive integer indicating the number of seconds before a subsequent attempt could succeed"))
  public LinkDeviceToken createDeviceToken(@ReadOnly @Auth AuthenticatedDevice auth)
      throws RateLimitExceededException, DeviceLimitExceededException {

    final Account account = auth.getAccount();

    rateLimiters.getAllocateDeviceLimiter().validate(account.getUuid());

    int maxDeviceLimit = MAX_DEVICES;

    if (maxDeviceConfiguration.containsKey(account.getNumber())) {
      maxDeviceLimit = maxDeviceConfiguration.get(account.getNumber());
    }

    if (account.getDevices().size() >= maxDeviceLimit) {
      throw new DeviceLimitExceededException(account.getDevices().size(), maxDeviceLimit);
    }

    if (auth.getAuthenticatedDevice().getId() != Device.PRIMARY_ID) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    final String token = accounts.generateLinkDeviceToken(account.getUuid());

    return new LinkDeviceToken(token, AccountsManager.getLinkDeviceTokenIdentifier(token));
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/link")
  @ChangesLinkedDevices
  @Operation(summary = "Link a device to an account",
      description = """
          Links a device to an account identified by a given phone number.
          """)
  @ApiResponse(responseCode = "200", description = "The new device was linked to the calling account", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "403", description = "The given account was not found or the given verification code was incorrect")
  @ApiResponse(responseCode = "409", description = "The new device is missing a capability supported by all other devices on the account")
  @ApiResponse(responseCode = "411", description = "The given account already has its maximum number of linked devices")
  @ApiResponse(responseCode = "422", description = "The request did not pass validation")
  @ApiResponse(responseCode = "429", description = "Too many attempts", headers = @Header(
      name = "Retry-After",
      description = "If present, an positive integer indicating the number of seconds before a subsequent attempt could succeed"))
  public LinkDeviceResponse linkDevice(@HeaderParam(HttpHeaders.AUTHORIZATION) BasicAuthorizationHeader authorizationHeader,
      @HeaderParam(HttpHeaders.USER_AGENT) @Nullable String userAgent,
      @NotNull @Valid LinkDeviceRequest linkDeviceRequest,
      @Context ContainerRequest containerRequest)
      throws RateLimitExceededException, DeviceLimitExceededException {

    final Account account = accounts.checkDeviceLinkingToken(linkDeviceRequest.verificationCode())
        .flatMap(accounts::getByAccountIdentifier)
        .orElseThrow(ForbiddenException::new);

    final DeviceActivationRequest deviceActivationRequest = linkDeviceRequest.deviceActivationRequest();
    final AccountAttributes accountAttributes = linkDeviceRequest.accountAttributes();

    rateLimiters.getVerifyDeviceLimiter().validate(account.getUuid());

    final boolean allKeysValid =
        PreKeySignatureValidator.validatePreKeySignatures(account.getIdentityKey(IdentityType.ACI),
            List.of(deviceActivationRequest.aciSignedPreKey(), deviceActivationRequest.aciPqLastResortPreKey()),
            userAgent,
            "link-device")
            && PreKeySignatureValidator.validatePreKeySignatures(account.getIdentityKey(IdentityType.PNI),
            List.of(deviceActivationRequest.pniSignedPreKey(), deviceActivationRequest.pniPqLastResortPreKey()),
            userAgent,
            "link-device");

    if (!allKeysValid) {
      throw new WebApplicationException(Response.status(422).build());
    }

    // Normally, the "do we need to refresh somebody's websockets" listener can do this on its own. In this case,
    // we're not using the conventional authentication system, and so we need to give it a hint so it knows who the
    // active user is and what their device states look like.
    LinkedDeviceRefreshRequirementProvider.setAccount(containerRequest, account);

    final int maxDeviceLimit = maxDeviceConfiguration.getOrDefault(account.getNumber(), MAX_DEVICES);

    if (account.getDevices().size() >= maxDeviceLimit) {
      throw new DeviceLimitExceededException(account.getDevices().size(), maxDeviceLimit);
    }

    final DeviceCapabilities capabilities = accountAttributes.getCapabilities();

    if (capabilities == null) {
      throw new WebApplicationException(Response.status(422, "Missing device capabilities").build());
    } else if (isCapabilityDowngrade(account, capabilities)) {
      throw new WebApplicationException(Response.status(409).build());
    }

    final String signalAgent;

    if (deviceActivationRequest.apnToken().isPresent()) {
      signalAgent = "OWP";
    } else if (deviceActivationRequest.gcmToken().isPresent()) {
      signalAgent = "OWA";
    } else {
      signalAgent = "OWD";
    }

    try {
      return accounts.addDevice(account, new DeviceSpec(accountAttributes.getName(),
                  authorizationHeader.getPassword(),
                  signalAgent,
                  capabilities,
                  accountAttributes.getRegistrationId(),
                  accountAttributes.getPhoneNumberIdentityRegistrationId(),
                  accountAttributes.getFetchesMessages(),
                  deviceActivationRequest.apnToken(),
                  deviceActivationRequest.gcmToken(),
                  deviceActivationRequest.aciSignedPreKey(),
                  deviceActivationRequest.pniSignedPreKey(),
                  deviceActivationRequest.aciPqLastResortPreKey(),
                  deviceActivationRequest.pniPqLastResortPreKey()),
              linkDeviceRequest.verificationCode())
          .thenApply(accountAndDevice -> new LinkDeviceResponse(
              accountAndDevice.first().getIdentifier(IdentityType.ACI),
              accountAndDevice.first().getIdentifier(IdentityType.PNI),
              accountAndDevice.second().getId()))
          .join();
    } catch (final CompletionException e) {
      if (e.getCause() instanceof LinkDeviceTokenAlreadyUsedException) {
        throw new ForbiddenException();
      }

      throw e;
    }
  }

  @GET
  @Path("/wait_for_linked_device/{tokenIdentifier}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Wait for a new device to be linked to an account",
      description = """
          Waits for a new device to be linked to an account and returns basic information about the new device when
          available.
          """)
  @ApiResponse(responseCode = "200", description = "The specified was linked to an account",
      content = @Content(schema = @Schema(implementation = DeviceInfo.class)))
  @ApiResponse(responseCode = "204", description = "No device was linked to the account before the call completed")
  @ApiResponse(responseCode = "400", description = "The given token identifier or timeout was invalid")
  @ApiResponse(responseCode = "429", description = "Rate-limited; try again after the prescribed delay")
  public CompletableFuture<Response> waitForLinkedDevice(
      @ReadOnly @Auth final AuthenticatedDevice authenticatedDevice,

      @PathParam("tokenIdentifier")
      @Schema(description = "A 'link device' token identifier provided by the 'create link device token' endpoint")
      @Size(min = MIN_TOKEN_IDENTIFIER_LENGTH, max = MAX_TOKEN_IDENTIFIER_LENGTH)
      final String tokenIdentifier,

      @QueryParam("timeout")
      @DefaultValue("30")
      @Min(1)
      @Max(3600)
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
          minimum = "1",
          maximum = "3600",
          description = """
                The amount of time (in seconds) to wait for a response. If the expected device is not linked within the
                given amount of time, this endpoint will return a status of HTTP/204.
              """) final int timeoutSeconds,

      @HeaderParam(HttpHeaders.USER_AGENT) String userAgent) throws RateLimitExceededException {

    rateLimiters.getWaitForLinkedDeviceLimiter().validate(authenticatedDevice.getAccount().getIdentifier(IdentityType.ACI));

    final AtomicInteger linkedDeviceListenerCounter = getCounterForLinkedDeviceListeners(userAgent);
    linkedDeviceListenerCounter.incrementAndGet();

    final Timer.Sample sample = Timer.start();

    try {
      return accounts.waitForNewLinkedDevice(tokenIdentifier, Duration.ofSeconds(timeoutSeconds))
          .thenApply(maybeDeviceInfo -> maybeDeviceInfo
              .map(deviceInfo -> Response.status(Response.Status.OK).entity(deviceInfo).build())
              .orElseGet(() -> Response.status(Response.Status.NO_CONTENT).build()))
          .exceptionally(ExceptionUtils.exceptionallyHandler(IllegalArgumentException.class,
              e -> Response.status(Response.Status.BAD_REQUEST).build()))
          .whenComplete((response, throwable) -> {
            linkedDeviceListenerCounter.decrementAndGet();

            if (response != null) {
              sample.stop(Timer.builder(WAIT_FOR_LINKED_DEVICE_TIMER_NAME)
                  .publishPercentileHistogram(true)
                  .tags(Tags.of(UserAgentTagUtil.getPlatformTag(userAgent),
                      io.micrometer.core.instrument.Tag.of("deviceFound",
                          String.valueOf(response.getStatus() == Response.Status.OK.getStatusCode()))))
                  .register(Metrics.globalRegistry));
            }
          });
    } catch (final RedisException e) {
      // `waitForNewLinkedDevice` could fail synchronously if the Redis circuit breaker is open; prevent counter drift
      // if that happens
      linkedDeviceListenerCounter.decrementAndGet();
      throw e;
    }
  }

  private AtomicInteger getCounterForLinkedDeviceListeners(final String userAgent) {
    try {
      return linkedDeviceListenersByPlatform.get(UserAgentUtil.parseUserAgentString(userAgent).getPlatform());
    } catch (final UnrecognizedUserAgentException ignored) {
      return linkedDeviceListenersForUnrecognizedPlatforms;
    }
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/unauthenticated_delivery")
  public void setUnauthenticatedDelivery(@Mutable @Auth AuthenticatedDevice auth) {
    assert (auth.getAuthenticatedDevice() != null);
    // Deprecated
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/capabilities")
  public void setCapabilities(@Mutable @Auth AuthenticatedDevice auth, @NotNull @Valid DeviceCapabilities capabilities) {
    assert (auth.getAuthenticatedDevice() != null);
    final byte deviceId = auth.getAuthenticatedDevice().getId();
    accounts.updateDevice(auth.getAccount(), deviceId, d -> d.setCapabilities(capabilities));
  }

  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/public_key")
  @Operation(
      summary = "Sets a public key for authentication",
      description = """
          Sets the authentication public key for the authenticated device. The public key will be used for
          authentication in the nascent gRPC-over-Noise API. Existing devices must upload a public key before they can
          use the gRPC-over-Noise API, and this endpoint exists to facilitate migration to the new API.
          """
  )
  @ApiResponse(responseCode = "200", description = "Public key stored successfully")
  @ApiResponse(responseCode = "401", description = "Account authentication check failed")
  @ApiResponse(responseCode = "422", description = "Invalid request format")
  public CompletableFuture<Void> setPublicKey(@Auth final AuthenticatedDevice auth,
      final SetPublicKeyRequest setPublicKeyRequest) {

    return clientPublicKeysManager.setPublicKey(auth.getAccount(),
        auth.getAuthenticatedDevice().getId(),
        setPublicKeyRequest.publicKey());
  }

  private static boolean isCapabilityDowngrade(Account account, DeviceCapabilities capabilities) {
    boolean isDowngrade = false;
    isDowngrade |= account.isDeleteSyncSupported() && !capabilities.deleteSync();
    isDowngrade |= account.isVersionedExpirationTimerSupported() && !capabilities.versionedExpirationTimer();
    return isDowngrade;
  }
}
