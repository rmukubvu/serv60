/// Supplies the current access token for an API request.
abstract interface class AccessTokenProvider {
  Future<String?> accessToken();
}

/// Used while the app is running against an unauthenticated local API.
class NoopAccessTokenProvider implements AccessTokenProvider {
  @override
  Future<String?> accessToken() async => null;
}

/// Adapter for an OIDC/Cognito client owned by the application shell.
class CallbackAccessTokenProvider implements AccessTokenProvider {
  CallbackAccessTokenProvider(this._loader);

  final Future<String?> Function() _loader;

  @override
  Future<String?> accessToken() => _loader();
}
