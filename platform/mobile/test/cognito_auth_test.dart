import 'package:flutter_test/flutter_test.dart';

import '../lib/src/auth/cognito_auth.dart';

class FakeSessionStore implements AuthSessionStore {
  AuthSession? value;

  @override
  Future<AuthSession?> read() async => value;

  @override
  Future<void> write(AuthSession session) async => value = session;

  @override
  Future<void> clear() async => value = null;
}

class FakeAuthorizationClient implements CognitoAuthorizationClient {
  int refreshCalls = 0;
  AuthTokenResponse authorizationResponse = const AuthTokenResponse(
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    expiresAt: null,
    idToken: 'id-1',
  );

  @override
  Future<AuthTokenResponse> authorize(
          CognitoAuthConfiguration configuration) async =>
      authorizationResponse;

  @override
  Future<AuthTokenResponse> refresh(
      CognitoAuthConfiguration configuration, String refreshToken) async {
    refreshCalls++;
    return const AuthTokenResponse(
      accessToken: 'access-2',
      refreshToken: 'refresh-2',
      expiresAt: null,
      idToken: 'id-2',
    );
  }

  @override
  Future<void> endSession(CognitoAuthConfiguration configuration,
      {String? idTokenHint}) async {}
}

const configuration = CognitoAuthConfiguration(
  discoveryUrl: 'https://auth.example.test/.well-known/openid-configuration',
  clientId: 'client-1',
  redirectUrl: 'serv60://oauth/callback',
);

void main() {
  test('signs in with authorization code PKCE and persists the session',
      () async {
    final store = FakeSessionStore();
    final authClient = FakeAuthorizationClient();
    final auth = CognitoAuthClient(
      configuration: configuration,
      authorizationClient: authClient,
      sessionStore: store,
      now: () => DateTime.utc(2026, 10, 12),
    );

    final session = await auth.signIn();

    expect(session.accessToken, 'access-1');
    expect(await auth.accessToken(), 'access-1');
    expect(store.value?.refreshToken, 'refresh-1');
  });

  test('refreshes an expiring session once for concurrent token requests',
      () async {
    final store = FakeSessionStore()
      ..value = AuthSession(
        accessToken: 'old',
        refreshToken: 'refresh-1',
        expiresAt: DateTime.utc(2026, 10, 12, 0, 0, 10),
      );
    final authClient = FakeAuthorizationClient();
    final auth = CognitoAuthClient(
      configuration: configuration,
      authorizationClient: authClient,
      sessionStore: store,
      now: () => DateTime.utc(2026, 10, 12),
    );
    await auth.restore();

    final tokens = await Future.wait([auth.accessToken(), auth.accessToken()]);

    expect(tokens, ['access-2', 'access-2']);
    expect(authClient.refreshCalls, 1);
  });

  test('returns no token when Cognito is not configured', () async {
    const unconfigured = CognitoAuthConfiguration(
      discoveryUrl: '',
      clientId: '',
      redirectUrl: 'serv60://oauth/callback',
    );
    final auth = CognitoAuthClient(
      configuration: unconfigured,
      sessionStore: FakeSessionStore(),
      authorizationClient: FakeAuthorizationClient(),
    );

    expect(await auth.accessToken(), isNull);
    expect(() => auth.signIn(), throwsA(isA<StateError>()));
  });
}
