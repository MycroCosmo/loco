class UserModel {
  final String id;
  final String email;
  final String nickname;
  final String? profileImageUrl;
  final String provider;

  const UserModel({
    required this.id,
    required this.email,
    required this.nickname,
    this.profileImageUrl,
    required this.provider,
  });

  factory UserModel.fromJson(Map<String, dynamic> json) {
    return UserModel(
      id: json['id'] as String,
      email: json['email'] as String,
      nickname: json['nickname'] as String,
      profileImageUrl: json['profileImageUrl'] as String?,
      provider: json['provider'] as String? ?? 'LOCAL',
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'email': email,
        'nickname': nickname,
        'profileImageUrl': profileImageUrl,
        'provider': provider,
      };
}
