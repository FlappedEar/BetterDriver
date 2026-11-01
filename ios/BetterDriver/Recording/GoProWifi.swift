import BetterDriverCore
import Foundation
import NetworkExtension

/// One Open GoPro camera's Wi-Fi on the iPhone (FBBD-343), as the Android app's AndroidWifiJoiner and
/// GoProHttp: the shared Kotlin download (GoProDownload) asks for each step and this does it. It joins
/// the camera's access point with NEHotspotConfiguration (iOS asks the driver once), and talks to the
/// camera's HTTP API with URLSession over Wi-Fi only (the camera's network has no internet, so cellular
/// must never take the request). Everything answers on the main queue, where the Kotlin side waits.
final class GoProWifi: IosGoProWifi, URLSessionDownloadDelegate {
    private var session: URLSession?
    /// Where the running download goes, and why moving it there failed.
    private var target: String?
    private var moveError: String?

    private func urlSession() -> URLSession {
        if let session { return session }
        let config = URLSessionConfiguration.ephemeral
        config.allowsCellularAccess = false
        config.waitsForConnectivity = false
        // As Android's GoProHttp: a camera silent for 10 s is gone.
        config.timeoutIntervalForRequest = 10
        let made = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        session = made
        return made
    }

    override func beginJoin(ssid: String, password: String) {
        let config = NEHotspotConfiguration(ssid: ssid, passphrase: password, isWEP: false)
        // Only for this download: iOS forgets the network when the app leaves it.
        config.joinOnce = true
        NEHotspotConfigurationManager.shared.apply(config) { [weak self] error in
            DispatchQueue.main.async {
                guard let self else { return }
                if let error = error as NSError?,
                   !(error.domain == NEHotspotConfigurationErrorDomain
                     && error.code == NEHotspotConfigurationError.alreadyAssociated.rawValue) {
                    AppLog.w("GoPro", "Wi-Fi join failed: \(error.domain) \(error.code) \(error.localizedDescription)")
                    self.joined(error: "could not join the GoPro's Wi-Fi \(ssid): \(error.localizedDescription)")
                } else {
                    AppLog.i("GoPro", "Wi-Fi joined")
                    self.joined(error: nil)
                }
            }
        }
    }

    override func beginLeave(ssid: String) {
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
        // The session holds this object as its delegate until invalidated.
        session?.finishTasksAndInvalidate()
        session = nil
        AppLog.i("GoPro", "Wi-Fi left")
    }

    override func beginGet(url: String) {
        guard let u = URL(string: url) else {
            got(body: nil, error: "bad address \(url)")
            return
        }
        urlSession().dataTask(with: u) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self else { return }
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                if let error {
                    self.got(body: nil, error: error.localizedDescription)
                } else if status != 200 {
                    self.got(body: nil, error: "GET \(u.path): HTTP \(status)")
                } else {
                    self.got(body: String(decoding: data ?? Data(), as: UTF8.self), error: nil)
                }
            }
        }.resume()
    }

    override func beginDownload(url: String, toPath: String) {
        guard let u = URL(string: url) else {
            downloaded(error: "bad address \(url)")
            return
        }
        target = toPath
        moveError = nil
        urlSession().downloadTask(with: u).resume()
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        downloadProgress(bytes: totalBytesWritten, total: max(0, totalBytesExpectedToWrite))
    }

    /// The file must be moved before this returns: URLSession deletes it afterwards.
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            moveError = "GET \(downloadTask.originalRequest?.url?.path ?? ""): HTTP \(status)"
            return
        }
        guard let target else { return }
        do {
            let to = URL(fileURLWithPath: target)
            try? FileManager.default.removeItem(at: to)
            try FileManager.default.moveItem(at: location, to: to)
        } catch {
            moveError = "could not keep the file: \(error.localizedDescription)"
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // Only downloads come here: a data task with a completion handler answers there.
        guard task is URLSessionDownloadTask else { return }
        target = nil
        downloaded(error: error?.localizedDescription ?? moveError)
    }
}
