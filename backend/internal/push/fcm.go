// Lumos - backend/internal/push / The FCM HTTP v1 adapter, authenticated by workload identity.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, TRS-BCK-012, SRS-RMT-006, TRS-BCK-002
// ADRs: -
// Threading: concurrent-safe
// Notes: The message is data-only and fixed: {"lumos":"wake"}, high priority,
// one collapse key so a burst of messages becomes one wake. There is no
// notification block, so Google never sees display text; the app shows its
// own generic text (SRS-RMT-006). Authentication uses the access token of the
// service account the process runs as, fetched from the GCP metadata server
// (TRS-BCK-012: workload identity, no key file exists anywhere). The adapter
// is complete and tested against fake servers; sending through real FCM needs
// the owner to grant that service account FCM permission, which is an
// owner-executed step.
package push

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sync"
	"time"
)

const (
	fcmEndpoint  = "https://fcm.googleapis.com"
	fcmScope     = "https://www.googleapis.com/auth/firebase.messaging"
	metadataBase = "http://metadata.google.internal"
	// collapseKey merges pending wakes on both providers.
	collapseKey = "lumos-wake"
	// tokenRefreshMargin renews an access token this long before it
	// expires, so no request is sent with one about to lapse.
	tokenRefreshMargin = 5 * time.Minute
	// maxProviderBody bounds how much of a provider's error reply is read.
	maxProviderBody = 64 << 10
)

// fcmWake is the whole message body except the token.
type fcmWake struct {
	Message struct {
		Token   string            `json:"token"`
		Data    map[string]string `json:"data"`
		Android struct {
			Priority    string `json:"priority"`
			CollapseKey string `json:"collapse_key"`
		} `json:"android"`
	} `json:"message"`
}

// TokenSource yields an OAuth access token and forgets it on demand.
type TokenSource interface {
	Token(ctx context.Context) (string, error)
	Invalidate()
}

// MetadataTokenSource fetches the runtime service account's token from the
// GCP metadata server and caches it until shortly before it expires.
type MetadataTokenSource struct {
	base   string
	client *http.Client
	now    func() time.Time

	mu      sync.Mutex
	token   string
	expires time.Time
}

// NewMetadataTokenSource uses the metadata server every GCP runtime provides.
func NewMetadataTokenSource(client *http.Client) *MetadataTokenSource {
	return &MetadataTokenSource{base: metadataBase, client: client, now: time.Now}
}

// Token implements TokenSource.
func (m *MetadataTokenSource) Token(ctx context.Context) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.token != "" && m.now().Before(m.expires.Add(-tokenRefreshMargin)) {
		return m.token, nil
	}
	u := m.base + "/computeMetadata/v1/instance/service-accounts/default/token?scopes=" + url.QueryEscape(fcmScope)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Metadata-Flavor", "Google")
	resp, err := m.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("push: metadata server unreachable: %w", err)
	}
	defer func() { _ = resp.Body.Close() }() // a read-only response: nothing to lose on close
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("push: metadata server status %d", resp.StatusCode)
	}
	var body struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxProviderBody)).Decode(&body); err != nil || body.AccessToken == "" {
		return "", errors.New("push: metadata server returned no token")
	}
	m.token = body.AccessToken
	m.expires = m.now().Add(time.Duration(body.ExpiresIn) * time.Second)
	return m.token, nil
}

// Invalidate implements TokenSource.
func (m *MetadataTokenSource) Invalidate() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.token = ""
}

// FCM sends wakes through Firebase Cloud Messaging HTTP v1.
type FCM struct {
	endpoint string
	project  string
	tokens   TokenSource
	client   *http.Client
}

// NewFCM sends for the given Firebase project id.
func NewFCM(project string, tokens TokenSource, client *http.Client) *FCM {
	return &FCM{endpoint: fcmEndpoint, project: project, tokens: tokens, client: client}
}

// Send implements Sender.
func (f *FCM) Send(ctx context.Context, token string) error {
	var msg fcmWake
	msg.Message.Token = token
	msg.Message.Data = map[string]string{"lumos": "wake"}
	msg.Message.Android.Priority = "HIGH"
	msg.Message.Android.CollapseKey = collapseKey
	body, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	access, err := f.tokens.Token(ctx)
	if err != nil {
		return err
	}
	u := f.endpoint + "/v1/projects/" + url.PathEscape(f.project) + "/messages:send"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+access)
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.client.Do(req)
	if err != nil {
		return fmt.Errorf("push: fcm unreachable: %w", redactURL(err))
	}
	defer func() { _ = resp.Body.Close() }() // a read-only response: nothing to lose on close
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	var reply struct {
		Error struct {
			Details []struct {
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, maxProviderBody)).Decode(&reply)
	for _, d := range reply.Error.Details {
		// UNREGISTERED: the app was uninstalled or the token expired.
		// SENDER_ID_MISMATCH: the token belongs to another Firebase project.
		if d.ErrorCode == "UNREGISTERED" || d.ErrorCode == "SENDER_ID_MISMATCH" {
			return ErrTokenGone
		}
	}
	if resp.StatusCode == http.StatusUnauthorized {
		f.tokens.Invalidate()
	}
	return fmt.Errorf("push: fcm status %d", resp.StatusCode)
}

// redactURL strips the request URL from a transport error. FCM's URL holds
// only the project id, but APNs's holds the device token, and one rule for
// both is easier to keep.
func redactURL(err error) error {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Err
	}
	return err
}
