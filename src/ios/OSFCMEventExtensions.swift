import OSFirebaseMessagingLib

extension OSFCMClickableType: CustomStringConvertible {
    public var description: String {
        var result: String
        switch self {
        case .notification(latestVersion: let latestVersion):
            result = "notificationClick"
            if latestVersion {
                result += "V2"
            }
        case .action:
            result = "internalRouteActionClick"
        }
        
        return result
    }
}

extension FirebaseNotificationType: CustomStringConvertible {
    public var description: String { "\(self == .silentNotification ? "silent": "default")Notification" }
}
