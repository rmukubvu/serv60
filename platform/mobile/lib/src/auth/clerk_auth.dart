import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'access_token_provider.dart';

/// Clerk OIDC (Authorization Code + PKCE). Enable Google in the Clerk
/// dashboard; the app opens Clerk's authorize URL and returns a JWT.
class ClerkAuthConfiguration {
  const ClerkAuthConfiguration({
    required this.discoveryUrl,
    required this.clientId,
    required this.redirectUrl,
    this.scopes = const ['openid', 'email', 'profile', 'offline_access'],
    this.postLogoutRedirectUrl,
  });

  factory ClerkAuthConfiguration.fromEnvironment() {
    final frontendApi = _trimTrailingSlash(
      const String.fromEnvironment('SERV60_CLERK_FRONTEND_API'),
    );
    return ClerkAuthConfiguration(
      discoveryUrl: frontendApi.isEmpty
          ? ''
          : '$frontendApi/.well-known/openid-configuration',
      clientId: const String.fromEnvironment('SERV60_CLERK_OAUTH_CLIENT_ID'),
      // Trailing slash: AppAuth on iOS often normalizes to this form; Clerk
      // redirect allowlists must match exactly.
      redirectUrl: const String.fromEnvironment(
        'SERV60_CLERK_REDIRECT_URL',
        defaultValue: 'serv60://oauth/callback/',
      ),
      postLogoutRedirectUrl: const String.fromEnvironment(
        'SERV60_CLERK_LOGOUT_REDIRECT_URL',
        defaultValue: 'serv60://oauth/logout/',
      ),
    );
  }

  final String discoveryUrl;
  final String clientId;
  final String redirectUrl;
  final List<String> scopes;
  final String? postLogoutRedirectUrl;

  bool get isConfigured => discoveryUrl.isNotEmpty && clientId.isNotEmpty;
}

String _trimTrailingSlash(String value) =>
    value.endsWith('/') ? value.substring(0, value.length - 1) : value;

class AuthSession {
  const AuthSession({
    required this.accessToken,
    required this.refreshToken,
    required this.expiresAt,
    this.idToken,
  });

  final String accessToken;
  final String? refreshToken;
  final DateTime expiresAt;
  final String? idToken;

  bool expiresWithin(Duration window, DateTime now) =>
      !expiresAt.toUtc().isAfter(now.toUtc().add(window));

  Map<String, dynamic> toJson() => {
        'accessToken': accessToken,
        'refreshToken': refreshToken,
        'expiresAt': expiresAt.toUtc().toIso8601String(),
        'idToken': idToken,
      };

  factory AuthSession.fromJson(Map<String, dynamic> json) => AuthSession(
        accessToken: json['accessToken'] as String,
        refreshToken: json['refreshToken'] as String?,
        expiresAt: DateTime.parse(json['expiresAt'] as String),
        idToken: json['idToken'] as String?,
      );
}

abstract interface class AuthSessionStore {
  Future<AuthSession?> read();

  Future<void> write(AuthSession session);

  Future<void> clear();
}

class SecureAuthSessionStore implements AuthSessionStore {
  SecureAuthSessionStore({FlutterSecureStorage? storage})
      : storage = storage ?? const FlutterSecureStorage();

  static const key = 'serv60.auth.session';
  final FlutterSecureStorage storage;

  @override
  Future<AuthSession?> read() async {
    final value = await storage.read(key: key);
    if (value == null || value.isEmpty) return null;
    try {
      return AuthSession.fromJson(jsonDecode(value) as Map<String, dynamic>);
    } on FormatException {
      await clear();
      return null;
    } on TypeError {
      await clear();
      return null;
    }
  }

  @override
  Future<void> write(AuthSession session) =>
      storage.write(key: key, value: jsonEncode(session.toJson()));

  @override
  Future<void> clear() => storage.delete(key: key);
}

class AuthTokenResponse {
  const AuthTokenResponse({
    required this.accessToken,
    required this.refreshToken,
    required this.expiresAt,
    this.idToken,
  });

  final String? accessToken;
  final String? refreshToken;
  final DateTime? expiresAt;
  final String? idToken;
}

abstract interface class ClerkAuthorizationClient {
  Future<AuthTokenResponse> authorize(ClerkAuthConfiguration configuration);

  Future<AuthTokenResponse> refresh(
      ClerkAuthConfiguration configuration, String refreshToken);

  Future<void> endSession(ClerkAuthConfiguration configuration,
      {String? idTokenHint});
}

class FlutterAppAuthAuthorizationClient implements ClerkAuthorizationClient {
  FlutterAppAuthAuthorizationClient({FlutterAppAuth? appAuth})
      : appAuth = appAuth ?? const FlutterAppAuth();

  final FlutterAppAuth appAuth;

  @override
  Future<AuthTokenResponse> authorize(
      ClerkAuthConfiguration configuration) async {
    final response = await appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        configuration.clientId,
        configuration.redirectUrl,
        discoveryUrl: configuration.discoveryUrl,
        scopes: configuration.scopes,
        // Clerk rejects prompt=select_account (and mis-encodes list forms).
      ),
    );
    return AuthTokenResponse(
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      expiresAt: response.accessTokenExpirationDateTime,
      idToken: response.idToken,
    );
  }

  @override
  Future<AuthTokenResponse> refresh(
      ClerkAuthConfiguration configuration, String refreshToken) async {
    final response = await appAuth.token(
      TokenRequest(
        configuration.clientId,
        configuration.redirectUrl,
        discoveryUrl: configuration.discoveryUrl,
        scopes: configuration.scopes,
        refreshToken: refreshToken,
      ),
    );
    return AuthTokenResponse(
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      expiresAt: response.accessTokenExpirationDateTime,
      idToken: response.idToken,
    );
  }

  @override
  Future<void> endSession(ClerkAuthConfiguration configuration,
      {String? idTokenHint}) async {
    await appAuth.endSession(
      EndSessionRequest(
        postLogoutRedirectUrl: configuration.postLogoutRedirectUrl,
        discoveryUrl: configuration.discoveryUrl,
        idTokenHint: idTokenHint,
      ),
    );
  }
}

class ClerkAuthClient implements AccessTokenProvider {
  ClerkAuthClient({
    required this.configuration,
    ClerkAuthorizationClient? authorizationClient,
    AuthSessionStore? sessionStore,
    DateTime Function()? now,
  })  : authorizationClient =
            authorizationClient ?? FlutterAppAuthAuthorizationClient(),
        sessionStore = sessionStore ?? SecureAuthSessionStore(),
        now = now ?? (() => DateTime.now().toUtc());

  final ClerkAuthConfiguration configuration;
  final ClerkAuthorizationClient authorizationClient;
  final AuthSessionStore sessionStore;
  final DateTime Function() now;
  Future<AuthSession>? _refreshInFlight;
  AuthSession? _session;

  AuthSession? get session => _session;
  bool get isConfigured => configuration.isConfigured;
  bool get isSignedIn => _session != null;

  Future<AuthSession?> restore() async {
    _session = await sessionStore.read();
    if (_session == null) return null;
    try {
      return await _validSession();
    } catch (_) {
      await signOut();
      return null;
    }
  }

  Future<AuthSession> signIn() async {
    if (!isConfigured) {
      throw StateError('Clerk authentication is not configured');
    }
    final response = await authorizationClient.authorize(configuration);
    final session = _sessionFrom(response, requireRefreshToken: false);
    _session = session;
    await sessionStore.write(session);
    return session;
  }

  Future<void> signOut() async {
    final current = _session;
    _session = null;
    await sessionStore.clear();
    if (current?.idToken != null && isConfigured) {
      try {
        await authorizationClient.endSession(configuration,
            idTokenHint: current!.idToken);
      } on Exception {
        // Local sign-out remains complete if Clerk logout is unavailable.
      }
    }
  }

  @override
  Future<String?> accessToken() async {
    if (_session == null) await restore();
    final current = _session;
    if (current == null) return null;
    final valid = await _validSession();
    return valid.accessToken;
  }

  Future<AuthSession> _validSession() async {
    final current = _session!;
    if (!current.expiresWithin(const Duration(seconds: 30), now())) {
      return current;
    }
    final refreshToken = current.refreshToken;
    if (refreshToken == null || refreshToken.isEmpty) {
      await signOut();
      throw StateError('Clerk session expired');
    }
    _refreshInFlight ??= _refresh(refreshToken);
    try {
      return await _refreshInFlight!;
    } finally {
      _refreshInFlight = null;
    }
  }

  Future<AuthSession> _refresh(String refreshToken) async {
    final response =
        await authorizationClient.refresh(configuration, refreshToken);
    final refreshed = _sessionFrom(response,
        requireRefreshToken: true, fallbackRefreshToken: refreshToken);
    _session = refreshed;
    await sessionStore.write(refreshed);
    return refreshed;
  }

  AuthSession _sessionFrom(AuthTokenResponse response,
      {required bool requireRefreshToken, String? fallbackRefreshToken}) {
    final accessToken = response.accessToken;
    if (accessToken == null || accessToken.trim().isEmpty) {
      throw StateError('Clerk did not return an access token');
    }
    final refreshToken = response.refreshToken ?? fallbackRefreshToken;
    if (requireRefreshToken && (refreshToken == null || refreshToken.isEmpty)) {
      throw StateError(
          'Clerk refresh response did not include a refresh token');
    }
    return AuthSession(
      accessToken: accessToken,
      refreshToken: refreshToken,
      expiresAt: response.expiresAt ?? now().add(const Duration(minutes: 5)),
      idToken: response.idToken,
    );
  }
}

class ClerkAuthController extends ChangeNotifier
    implements AccessTokenProvider {
  ClerkAuthController(this.client);

  final ClerkAuthClient client;
  bool loading = false;
  Object? error;

  bool get isConfigured => client.isConfigured;
  bool get isSignedIn => client.isSignedIn;

  /// Best-effort email from the ID token — Clerk identity, not editable in-app.
  String? get displayEmail => _idClaim('email');

  /// Best-effort full name from the ID token — Clerk identity, not editable in-app.
  String? get displayName {
    final name = _idClaim('name');
    if (name != null && name.trim().isNotEmpty) return name.trim();
    final given = _idClaim('given_name') ?? '';
    final family = _idClaim('family_name') ?? '';
    final combined = '$given $family'.trim();
    return combined.isEmpty ? null : combined;
  }

  String? _idClaim(String key) {
    final token = client.session?.idToken;
    if (token == null) return null;
    try {
      final parts = token.split('.');
      if (parts.length < 2) return null;
      var payload = parts[1].replaceAll('-', '+').replaceAll('_', '/');
      while (payload.length % 4 != 0) {
        payload += '=';
      }
      final claims = jsonDecode(utf8.decode(base64Decode(payload)));
      if (claims is Map && claims[key] is String) {
        return claims[key] as String;
      }
    } catch (_) {
      return null;
    }
    return null;
  }

  Future<void> restore() async {
    loading = true;
    notifyListeners();
    try {
      await client.restore();
      error = null;
    } catch (caught) {
      error = caught;
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  Future<void> signIn() async {
    loading = true;
    notifyListeners();
    try {
      await client.signIn();
      error = null;
    } catch (caught) {
      error = caught;
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  Future<void> signOut() async {
    await client.signOut();
    notifyListeners();
  }

  @override
  Future<String?> accessToken() => client.accessToken();
}
