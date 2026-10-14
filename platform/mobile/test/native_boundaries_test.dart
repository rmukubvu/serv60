import 'dart:async';
import 'package:app_links/app_links.dart';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:serv60_mobile/src/auth/clerk_auth.dart';
import 'package:serv60_mobile/src/payment/payment_checkout.dart';
import 'clerk_auth_test.dart'
    show FakeSessionStore, FakeAuthorizationClient, configuration;
import 'payment_checkout_test.dart'
    show FakePaymentRepository, FakeLauncher, order;

class NativeAuth extends FlutterAppAuth {
  AuthorizationTokenRequest? authorization;
  TokenRequest? refreshRequest;
  EndSessionRequest? logout;
  @override
  Future<AuthorizationTokenResponse> authorizeAndExchangeCode(
      AuthorizationTokenRequest request) async {
    authorization = request;
    return AuthorizationTokenResponse('access', 'refresh', DateTime.utc(2035),
        'id', 'Bearer', ['openid'], {}, {});
  }

  @override
  Future<TokenResponse> token(TokenRequest request) async {
    refreshRequest = request;
    return TokenResponse(
        'next', null, DateTime.utc(2035), 'id', 'Bearer', ['openid'], {});
  }

  @override
  Future<EndSessionResponse> endSession(EndSessionRequest request) async {
    logout = request;
    return EndSessionResponse('state');
  }
}

class NativeLinks extends Fake implements AppLinks {
  final events = StreamController<Uri>.broadcast();
  @override
  Stream<Uri> get uriLinkStream => events.stream;
  @override
  Future<Uri?> getInitialLink() async =>
      Uri.parse('serv60://payment-return?paymentId=payment-1');
}

class BrokenStore extends FakeSessionStore {
  @override
  Future<AuthSession?> read() async => throw StateError('Storage unavailable');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('secure session store clears corrupt JSON and persists valid sessions',
      () async {
    FlutterSecureStorage.setMockInitialValues({});
    final store = SecureAuthSessionStore();
    expect(await store.read(), isNull);
    final session = AuthSession(
        accessToken: 'a',
        refreshToken: 'r',
        expiresAt: DateTime.utc(2035),
        idToken: 'i');
    await store.write(session);
    expect((await store.read())!.toJson(), session.toJson());
    await store.clear();
    expect(await store.read(), isNull);
    for (final invalid in ['broken', '{"accessToken":42}', '[]']) {
      await store.storage
          .write(key: SecureAuthSessionStore.key, value: invalid);
      expect(await store.read(), isNull);
      expect(await store.storage.read(key: SecureAuthSessionStore.key), isNull);
    }
  });
  test('native auth adapter preserves PKCE request and refresh/logout fields',
      () async {
    final native = NativeAuth();
    final adapter = FlutterAppAuthAuthorizationClient(appAuth: native);
    expect((await adapter.authorize(configuration)).accessToken, 'access');
    expect(native.authorization!.promptValues, ['select_account']);
    expect(native.authorization!.discoveryUrl, configuration.discoveryUrl);
    expect(
        (await adapter.refresh(configuration, 'refresh')).accessToken, 'next');
    expect(native.refreshRequest!.refreshToken, 'refresh');
    await adapter.endSession(configuration, idTokenHint: 'id');
    expect(native.logout!.idTokenHint, 'id');
  });
  test(
      'controller reports sign-in and storage failures and recovers after sign-out',
      () async {
    final store = FakeSessionStore();
    final provider = FakeAuthorizationClient();
    final controller = ClerkAuthController(ClerkAuthClient(
        configuration: configuration,
        authorizationClient: provider,
        sessionStore: store));
    await controller.restore();
    expect(controller.isSignedIn, isFalse);
    await controller.signIn();
    expect(controller.isSignedIn, isTrue);
    expect(await controller.accessToken(), 'access-1');
    await controller.signOut();
    expect(controller.isSignedIn, isFalse);
    expect(store.value, isNull);
    provider.authorizationResponse = const AuthTokenResponse(
        accessToken: '', refreshToken: null, expiresAt: null);
    await controller.signIn();
    expect(controller.error, isA<StateError>());
    expect(controller.loading, isFalse);
    controller.dispose();
    final failed = ClerkAuthController(ClerkAuthClient(
        configuration: configuration,
        sessionStore: BrokenStore(),
        authorizationClient: provider));
    await failed.restore();
    expect(failed.error, isA<StateError>());
    failed.dispose();
    expect(ClerkAuthConfiguration.fromEnvironment().isConfigured, isFalse);
  });
  test('expired sessions without a refresh token are removed', () async {
    final store = FakeSessionStore()
      ..value = AuthSession(
          accessToken: 'expired',
          refreshToken: null,
          expiresAt: DateTime.utc(2000));
    final auth = ClerkAuthClient(
        configuration: configuration,
        sessionStore: store,
        authorizationClient: FakeAuthorizationClient());
    expect(await auth.restore(), isNull);
    expect(auth.session, isNull);
    expect(store.value, isNull);
  });
  test('native payment links restore pending order and subscribe once',
      () async {
    FlutterSecureStorage.setMockInitialValues({});
    final store = SecurePendingCheckoutStore();
    await store.write('payment-1');
    expect(await store.read(), 'payment-1');
    final links = NativeLinks();
    final paid = order(status: 'PAID');
    final coordinator = PaymentCheckoutCoordinator(
        payments: FakePaymentRepository(paid, refreshed: paid),
        launcher: FakeLauncher(),
        pendingStore: store);
    await coordinator.listenForReturns(appLinks: links);
    expect(coordinator.order!.status, 'PAID');
    expect(await store.read(), isNull);
    await coordinator.listenForReturns(appLinks: links);
    links.events.add(Uri.parse('serv60://payment-return?paymentId=payment-1'));
    await Future<void>.delayed(Duration.zero);
    expect(coordinator.order!.id, 'payment-1');
    coordinator.dispose();
    await links.events.close();
  });

  test('checkout launcher uses the platform browser adapter', () async {
    const channel = MethodChannel('plugins.flutter.io/url_launcher');
    MethodCall? request;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      request = call;
      return true;
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    expect(
        await UrlLauncherHostedCheckout()
            .open(Uri.parse('https://checkout.yoco.test')),
        isTrue);
    expect(request!.arguments['url'], 'https://checkout.yoco.test');
  });
}
