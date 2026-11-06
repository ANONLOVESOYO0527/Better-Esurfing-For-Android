package engine

import (
	"fmt"
	"github.com/EchoPing07/better-esurfing-for-android/core/mockportal"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// Catches Stop returning while an old request still owns engine state.
func TestStopCancelsBlockedProbe(t *testing.T) {
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case entered <- struct{}{}:
		default:
		}
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer srv.Close()
	defer close(release)
	e := NewEngine(&Config{ProbeURLsJSON: fmt.Sprintf("[%q]", srv.URL), TimeoutSec: 30}, nil)
	if err := e.Start(&Account{Username: "test"}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("probe not started")
	}
	stopped := make(chan struct{})
	go func() { e.Stop(); close(stopped) }()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("Stop blocked on the active HTTP request")
	}
	select {
	case <-e.done:
	default:
		t.Fatal("Stop returned before loop exit")
	}
	if e.IsRunning() {
		t.Fatal("engine remains running")
	}
}

// Catches stale stop commands and previous loops changing a restarted engine.
func TestRepeatedStartStop(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer srv.Close()
	e := NewEngine(&Config{ProbeURLsJSON: fmt.Sprintf("[%q]", srv.URL)}, nil)
	for i := 0; i < 10; i++ {
		if err := e.Start(&Account{Username: "test"}); err != nil {
			t.Fatal(err)
		}
		e.Stop()
		if e.IsRunning() {
			t.Fatal("still running after stop")
		}
	}
}

// Catches an online session never noticing a captive portal when keepalive still succeeds.
func TestOnlineProbeDetectsLostAuthorization(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	e := NewEngine(&Config{ProbeURLsJSON: fmt.Sprintf("[%q]", mock.URL()+"/generate_204")}, nil)
	e.curAcc = &Account{Username: "test", Password: "pw"}
	e.authRound()
	if !e.online {
		t.Fatal("initial authentication failed", e.DumpLogs())
	}
	mock.SetRequireAuth(true)
	e.nextAction = time.Now().Add(-time.Second)
	e.probeOnline()
	if e.online {
		t.Fatal("captive portal was ignored while heartbeat succeeded")
	}
	if s, _ := e.CurrentState(); s != StateDetecting {
		t.Fatalf("state=%v", s)
	}
	e.detectPhase()
	if !e.online || mock.Logins() != 2 {
		t.Fatal("did not automatically authenticate again", e.DumpLogs())
	}
}

func TestOnlineProbeFailureDoesNotDropSession(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	e := NewEngine(&Config{ProbeURLsJSON: fmt.Sprintf("[%q]", mock.URL()+"/generate_204")}, nil)
	e.curAcc = &Account{Username: "test", Password: "pw"}
	e.authRound()
	if !e.online {
		t.Fatal("initial authentication failed", e.DumpLogs())
	}
	failed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Error(w, "temporary", 503) }))
	defer failed.Close()
	e.probeURLs = []string{failed.URL}
	e.nextAction = time.Now().Add(-time.Second)
	e.probeOnline()
	if !e.online {
		t.Fatal("transient probe failure dropped a healthy session")
	}
}

// A long server heartbeat deadline must not delay portal-loss detection.
func TestOnlineDetectionIndependentOfHeartbeat(t *testing.T) {
	mock := mockportal.New()
	defer mock.Close()
	mock.SetKeepInterval(300)
	e := NewEngine(&Config{ProbeURLsJSON: fmt.Sprintf("[%q]", mock.URL()+"/generate_204"), DetectIntervalSec: 1}, nil)
	defer e.Stop()
	if err := e.Start(&Account{Username: "test", Password: "pw"}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for mock.Logins() < 1 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if mock.Logins() != 1 {
		t.Fatal("initial login failed")
	}
	mock.SetRequireAuth(true)
	deadline = time.Now().Add(3 * time.Second)
	for mock.Logins() < 2 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if mock.Logins() != 2 {
		t.Fatal("portal detection waited for the 300 second heartbeat")
	}
}

func TestConcurrentCommandsAndRestart(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer srv.Close()
	e := NewEngine(&Config{ProbeURLsJSON: fmt.Sprintf("[%q]", srv.URL)}, nil)
	acc := &Account{Username: "test"}
	var workers sync.WaitGroup
	for worker := 0; worker < 3; worker++ {
		workers.Add(1)
		go func(worker int) {
			defer workers.Done()
			for i := 0; i < 50; i++ {
				switch worker {
				case 0:
					_ = e.Start(acc)
				case 1:
					_ = e.LoginNow(acc)
				case 2:
					e.Stop()
				}
			}
		}(worker)
	}
	workers.Wait()
	e.Stop()
}
