// Package libxray is a thin Android binding around Xray-core, built with
// `gomobile bind` into libxray.aar and consumed by app.slipnet.tunnel.XrayBridge.
//
// It is deliberately a trimmed-down sibling of 2dust/AndroidLibXrayLite (the
// wrapper v2rayNG uses): same lifecycle model (a CoreController owning one
// *core.Instance), same asset/env bootstrap, minus the pieces this app has no
// use for (TUN-fd handoff -- we bridge through hev-socks5-tunnel onto Xray's
// SOCKS inbound instead, and per-app UID routing).
package libxray

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	coreapplog "github.com/xtls/xray-core/app/log"
	corecommlog "github.com/xtls/xray-core/common/log"
	corenet "github.com/xtls/xray-core/common/net"
	corefilesystem "github.com/xtls/xray-core/common/platform/filesystem"
	core "github.com/xtls/xray-core/core"
	corestats "github.com/xtls/xray-core/features/stats"
	coreserial "github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	mobasset "golang.org/x/mobile/asset"
)

// Xray reads these from the process environment; there is no config-struct path.
const (
	coreAsset   = "xray.location.asset"
	coreCert    = "xray.location.cert"
	xudpBaseKey = "xray.xudp.basekey"
)

// LogHandler receives Xray's own log lines so the app can fold them into its
// AppLog (file logging + the in-app diagnostics screen). Optional: when no
// handler is registered the lines go to stdout, which gomobile's Android
// runtime already pumps into logcat under the "GoLog" tag.
type LogHandler interface {
	LogLine(line string)
}

// CoreCallbackHandler mirrors AndroidLibXrayLite's handler so the app can react
// to the core coming up or going down on its own (crashed) initiative.
type CoreCallbackHandler interface {
	Startup() int
	Shutdown() int
	OnEmitStatus(code int, message string) int
}

var (
	logMu      sync.RWMutex
	logHandler LogHandler
)

// SetLogHandler installs (or, with nil, removes) the sink for Xray log lines.
func SetLogHandler(h LogHandler) {
	logMu.Lock()
	logHandler = h
	logMu.Unlock()
}

func emitLog(line string) {
	logMu.RLock()
	h := logHandler
	logMu.RUnlock()
	if h == nil {
		log.Print(line)
		return
	}
	// A panicking Java handler must never take the core down with it.
	defer func() { _ = recover() }()
	h.LogLine(line)
}

// CoreController owns a single Xray instance. All lifecycle methods are
// serialised by coreMutex, so concurrent start/stop from the VPN service's
// worker thread and the UI thread is safe.
type CoreController struct {
	CallbackHandler CoreCallbackHandler
	statsManager    corestats.Manager
	coreMutex       sync.Mutex
	coreInstance    *core.Instance
	IsRunning       bool
}

// InitEnv points Xray at its asset directory (geoip.dat / geosite.dat / certs)
// and installs a file reader that falls back to the APK's bundled assets when a
// file is not present on disk -- so a user-supplied geoip.dat dropped into
// filesDir wins, and the AAR-bundled copies serve everyone else.
//
// envPath is the on-disk asset dir; key is the optional XUDP base key.
func InitEnv(envPath string, key string) {
	if len(envPath) > 0 {
		setEnv(coreAsset, envPath)
		setEnv(coreCert, envPath)
	}
	if len(key) > 0 {
		setEnv(xudpBaseKey, key)
	}

	corefilesystem.NewFileReader = func(path string) (io.ReadCloser, error) {
		if _, err := os.Stat(path); os.IsNotExist(err) {
			_, file := filepath.Split(path)
			return mobasset.Open(file)
		}
		return os.Open(path)
	}
}

// NewCoreController registers the console log handler and returns a controller.
func NewCoreController(handler CoreCallbackHandler) *CoreController {
	if err := coreapplog.RegisterHandlerCreator(
		coreapplog.LogType_Console,
		func(lt coreapplog.LogType, options coreapplog.HandlerCreatorOptions) (corecommlog.Handler, error) {
			return corecommlog.NewLogger(func() corecommlog.Writer { return &bridgeLogWriter{} }), nil
		},
	); err != nil {
		log.Printf("libxray: failed to register log handler: %v", err)
	}
	return &CoreController{CallbackHandler: handler}
}

// StartLoop parses configContent and starts the core. It is a no-op (nil error)
// when the core is already running.
func (x *CoreController) StartLoop(configContent string) error {
	x.coreMutex.Lock()
	defer x.coreMutex.Unlock()

	if x.IsRunning {
		emitLog("core is already running")
		return nil
	}
	return x.doStartLoop(configContent)
}

// StopLoop shuts the core down and releases its resources.
func (x *CoreController) StopLoop() error {
	x.coreMutex.Lock()
	defer x.coreMutex.Unlock()

	if x.IsRunning {
		x.doShutdown()
		if x.CallbackHandler != nil {
			x.CallbackHandler.OnEmitStatus(0, "Core stopped")
		}
	}
	return nil
}

// QueryStats reads and resets one outbound traffic counter.
// direct is "uplink" or "downlink". Returns 0 when stats are unavailable.
func (x *CoreController) QueryStats(tag string, direct string) int64 {
	if x.statsManager == nil {
		return 0
	}
	counter := x.statsManager.GetCounter(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
	if counter == nil {
		return 0
	}
	return counter.Set(0)
}

// MeasureDelay times a request to url through the running instance, in ms.
func (x *CoreController) MeasureDelay(url string) (int64, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	return measureInstDelay(ctx, x.coreInstance, url)
}

// TestConfig parses configContent the same way StartLoop does, without starting
// anything -- the app uses it to validate hand-edited JSON before saving.
func TestConfig(configContent string) error {
	_, err := coreserial.LoadJSONConfig(strings.NewReader(configContent))
	return err
}

// CheckVersion reports the linked Xray-core version.
func CheckVersion() string {
	return core.Version()
}

func (x *CoreController) doStartLoop(configContent string) error {
	emitLog("initializing core...")
	config, err := coreserial.LoadJSONConfig(strings.NewReader(configContent))
	if err != nil {
		return fmt.Errorf("config error: %w", err)
	}

	x.coreInstance, err = core.New(config)
	if err != nil {
		return fmt.Errorf("core init failed: %w", err)
	}
	if mgr, ok := x.coreInstance.GetFeature(corestats.ManagerType()).(corestats.Manager); ok {
		x.statsManager = mgr
	}

	x.IsRunning = true
	if err := x.coreInstance.Start(); err != nil {
		x.IsRunning = false
		// Start() failing leaves a half-built instance behind; drop it so a
		// retry does not inherit listeners the failed attempt already bound.
		x.doShutdown()
		return fmt.Errorf("startup failed: %w", err)
	}

	if x.CallbackHandler != nil {
		x.CallbackHandler.Startup()
		x.CallbackHandler.OnEmitStatus(0, "Started successfully, running")
	}
	emitLog("core started")
	return nil
}

func (x *CoreController) doShutdown() {
	if x.coreInstance != nil {
		if err := x.coreInstance.Close(); err != nil {
			emitLog(fmt.Sprintf("core shutdown error: %v", err))
		}
		x.coreInstance = nil
	}
	x.IsRunning = false
	x.statsManager = nil
}

func setEnv(key, value string) {
	if err := os.Setenv(key, value); err != nil {
		log.Printf("libxray: failed to set %s: %v", key, err)
	}
}

// bridgeLogWriter forwards Xray's log lines to the registered LogHandler.
// Android already timestamps logcat lines, so no date/time prefix is added.
type bridgeLogWriter struct{}

func (w *bridgeLogWriter) Write(s string) error {
	emitLog(s)
	return nil
}

func (w *bridgeLogWriter) Close() error { return nil }

func measureInstDelay(ctx context.Context, inst *core.Instance, url string) (int64, error) {
	if inst == nil {
		return -1, errors.New("core instance is nil")
	}
	if url == "" {
		url = "https://www.gstatic.com/generate_204"
	}

	tr := &http.Transport{
		TLSHandshakeTimeout: 6 * time.Second,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dest, err := corenet.ParseDestination(fmt.Sprintf("%s:%s", network, addr))
			if err != nil {
				return nil, err
			}
			return core.Dial(ctx, inst, dest)
		},
	}
	client := &http.Client{Transport: tr, Timeout: 12 * time.Second}

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return -1, err
	}

	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		return -1, fmt.Errorf("invalid status: %s", resp.Status)
	}
	if _, err := io.Copy(io.Discard, resp.Body); err != nil {
		return -1, err
	}
	return time.Since(start).Milliseconds(), nil
}
