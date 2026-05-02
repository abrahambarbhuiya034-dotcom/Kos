/**
 * CarromBot — v11.0 eSports Edition
 * Full eSports UI with multi-strategy AutoPlay bot engine
 */

import React, {useState, useEffect, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  Switch,
  ScrollView,
  Platform,
  StatusBar,
  NativeModules,
  Linking,
} from 'react-native';
import Slider from '@react-native-community/slider';

const {OverlayModule} = NativeModules;

const C = {
  bg:          '#060A14',
  bgDeep:      '#030609',
  card:        '#0B1220',
  cardBorder:  '#1A2744',
  neonBlue:    '#00D4FF',
  neonGreen:   '#00FF87',
  neonRed:     '#FF3355',
  neonOrange:  '#FF6B35',
  gold:        '#FFD700',
  purple:      '#9B59FF',
  textPrimary: '#E0EEFF',
  textDim:     '#5A7090',
  headerBg:    '#070C18',
};

function StatusDot({on}: {on: boolean}) {
  return (
    <View style={[styles.dot, {backgroundColor: on ? C.neonGreen : C.neonRed,
      shadowColor: on ? C.neonGreen : C.neonRed}]} />
  );
}

function SectionLabel({text}: {text: string}) {
  return (
    <View style={styles.sectionLabelRow}>
      <View style={styles.sectionLabelLine} />
      <Text style={styles.sectionLabelText}>{text}</Text>
      <View style={styles.sectionLabelLine} />
    </View>
  );
}

export default function App() {
  const [hasOverlay, setHasOverlay]           = useState(false);
  const [overlayActive, setOverlayActive]     = useState(false);
  const [autoDetect, setAutoDetect]           = useState(false);
  const [sensitivity, setSensitivity]         = useState(1.0);
  const [detectThreshold, setDetectThreshold] = useState(36);

  const [autoPlay, setAutoPlayState]          = useState(false);
  const [autoPlayDelay, setAutoPlayDelay]     = useState(2.0);
  const [accessibilityReady, setAccessibilityReady] = useState(false);

  useEffect(() => {
    refreshStatus();
    const t = setInterval(refreshStatus, 2000);
    return () => clearInterval(t);
  }, []);

  const refreshStatus = useCallback(async () => {
    try { setHasOverlay(await OverlayModule.canDrawOverlays()); } catch { setHasOverlay(true); }
    try { setAutoDetect(await OverlayModule.isAutoDetectActive()); } catch {}
    try { setAccessibilityReady(await OverlayModule.isAccessibilityReady()); } catch { setAccessibilityReady(false); }
    try { setAutoPlayState(await OverlayModule.isAutoPlayEnabled()); } catch {}
  }, []);

  const requestOverlay = useCallback(() => {
    try { OverlayModule.requestOverlayPermission(); setTimeout(refreshStatus, 1500); }
    catch { Alert.alert('Permission Needed', 'Grant "Display over other apps" in Settings.',
      [{text: 'Open Settings', onPress: () => Linking.openSettings()}]); }
  }, [refreshStatus]);

  const toggleOverlay = useCallback(async () => {
    if (!hasOverlay) { requestOverlay(); return; }
    try {
      if (overlayActive) {
        if (autoPlay) { try { await OverlayModule.setAutoPlay(false); } catch {} setAutoPlayState(false); }
        await OverlayModule.stopOverlay(); setOverlayActive(false); setAutoDetect(false);
      } else { await OverlayModule.startOverlay(); setOverlayActive(true); }
    } catch (e: any) { Alert.alert('Error', e.message || 'Could not toggle overlay'); }
  }, [hasOverlay, overlayActive, autoPlay, requestOverlay]);

  const toggleAutoDetect = useCallback(async () => {
    if (!overlayActive) { Alert.alert('Start Overlay First', 'Enable Aim Overlay before auto-detect.'); return; }
    try {
      if (autoDetect) { await OverlayModule.stopScreenCapture(); setAutoDetect(false); }
      else { await OverlayModule.requestScreenCapture(); setTimeout(refreshStatus, 2500); }
    } catch (e: any) { Alert.alert('Error', e.message || 'Could not toggle screen capture'); }
  }, [overlayActive, autoDetect, refreshStatus]);

  const toggleAutoPlay = useCallback(async () => {
    if (!overlayActive || !autoDetect) {
      Alert.alert('Prerequisites Missing', 'Enable Overlay + Auto-Detect first.'); return;
    }
    if (!accessibilityReady && !autoPlay) {
      Alert.alert('Accessibility Required',
        'Enable "AIMxASSIST" in Android Accessibility Settings so the bot can swipe the striker.',
        [{text: 'Open Accessibility', onPress: () => OverlayModule.requestAccessibilityPermission()},
         {text: 'Cancel', style: 'cancel'}]);
      return;
    }
    try {
      const next = !autoPlay;
      await OverlayModule.setAutoPlay(next);
      setAutoPlayState(next);
      if (next) Alert.alert('BOT ACTIVATED',
        'Switch to your carrom game now. The bot fires automatically on a stable board.',
        [{text: 'GO!'}]);
    } catch (e: any) {
      if (e.code === 'ERR_NO_ACCESSIBILITY')
        Alert.alert('Accessibility Not Ready', 'Enable "AIMxASSIST" in Accessibility Settings.',
          [{text: 'Open Settings', onPress: () => OverlayModule.requestAccessibilityPermission()}]);
      else Alert.alert('Error', e.message || 'Could not toggle autoplay');
    }
  }, [overlayActive, autoDetect, accessibilityReady, autoPlay]);

  const shootNow = useCallback(async () => {
    if (!overlayActive || !autoDetect) { Alert.alert('Not Ready', 'Overlay and Auto-Detect must be active.'); return; }
    if (!accessibilityReady) {
      Alert.alert('Accessibility Not Ready', 'Enable "AIMxASSIST" in Accessibility Settings.',
        [{text: 'Open Settings', onPress: () => OverlayModule.requestAccessibilityPermission()}]); return;
    }
    try { await OverlayModule.shootNow(); }
    catch (e: any) { Alert.alert('Shot Failed', e.message || 'Make sure you are in the carrom game'); }
  }, [overlayActive, autoDetect, accessibilityReady]);

  const handleSensitivityChange     = useCallback((v: number) => { setSensitivity(v); try { OverlayModule.setSensitivity(v); } catch {} }, []);
  const handleThresholdChange       = useCallback((v: number) => { setDetectThreshold(v); try { OverlayModule.setDetectionThreshold(v); } catch {} }, []);
  const handleAutoPlayDelayChange   = useCallback(async (v: number) => { setAutoPlayDelay(v); try { await OverlayModule.setAutoPlayDelay(Math.round(v * 1000)); } catch {} }, []);

  const allReady = overlayActive && autoDetect && accessibilityReady;

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={C.bgDeep} />

      {/* ── Header ── */}
      <View style={styles.header}>
        <View style={styles.headerTop}>
          <View>
            <Text style={styles.logo}>CARROM<Text style={styles.logoAccent}> BOT</Text></Text>
            <Text style={styles.subtitle}>eSports Aim Engine  •  v11.0</Text>
          </View>
          <View style={styles.liveBadge}>
            <View style={[styles.liveDot, {backgroundColor: allReady ? C.neonGreen : C.textDim}]} />
            <Text style={[styles.liveText, {color: allReady ? C.neonGreen : C.textDim}]}>
              {allReady ? 'LIVE' : 'IDLE'}
            </Text>
          </View>
        </View>

        {/* Status bar */}
        <View style={styles.statusBar}>
          <StatusItem label="OVERLAY"     on={overlayActive}      />
          <StatusItem label="VISION"      on={autoDetect}         />
          <StatusItem label="ACCESS"      on={accessibilityReady} />
          <StatusItem label="BOT"         on={autoPlay}           />
        </View>
      </View>

      <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>

        {/* ── Overlay permission banner ── */}
        {!hasOverlay && (
          <TouchableOpacity style={styles.alertBanner} onPress={requestOverlay}>
            <Text style={styles.alertIcon}>!</Text>
            <View style={{flex:1}}>
              <Text style={styles.alertTitle}>Overlay Permission Required</Text>
              <Text style={styles.alertSub}>Tap to grant "Display over other apps"</Text>
            </View>
            <Text style={styles.alertArrow}>›</Text>
          </TouchableOpacity>
        )}

        {/* ── Accessibility permission banner ── */}
        {!accessibilityReady && (
          <TouchableOpacity style={styles.accessBanner}
            onPress={() => OverlayModule.requestAccessibilityPermission()}>
            <Text style={styles.alertIcon}>⚡</Text>
            <View style={{flex:1}}>
              <Text style={styles.accessTitle}>Enable Accessibility for AutoPlay</Text>
              <Text style={styles.alertSub}>Settings → Accessibility → AIMxASSIST → ON</Text>
            </View>
            <Text style={styles.alertArrow}>›</Text>
          </TouchableOpacity>
        )}

        <SectionLabel text="BOT CONTROLS" />

        {/* ── Control Card ── */}
        <View style={styles.card}>
          <ToggleRow
            label="AIM OVERLAY"
            icon="◈"
            desc={overlayActive ? 'Running — overlay visible on game screen' : 'Tap to start overlay on carrom game'}
            value={overlayActive}
            onToggle={toggleOverlay}
            activeColor={C.gold}
          />
          <View style={styles.divider} />
          <ToggleRow
            label="VISION ENGINE"
            icon="◉"
            desc={autoDetect ? 'Screen capture active — detecting striker & coins' : 'Reads screen in real-time (one-time permission)'}
            value={autoDetect}
            onToggle={toggleAutoDetect}
            activeColor={C.neonBlue}
          />
        </View>

        <SectionLabel text="AUTO PLAY" />

        {/* ── AutoPlay Card ── */}
        <View style={[styles.card, autoPlay && styles.cardActive]}>
          <ToggleRow
            label="AUTO SHOOT BOT"
            icon="⚡"
            desc={autoPlay
              ? 'BOT ACTIVE — switch to carrom game now!'
              : 'Bot fires automatically on stable board'}
            value={autoPlay}
            onToggle={toggleAutoPlay}
            activeColor={C.neonGreen}
          />

          <View style={styles.divider} />

          <View style={styles.sliderRow}>
            <Text style={styles.sliderLabel}>FIRE DELAY</Text>
            <Text style={[styles.sliderValue, {color: C.neonGreen}]}>{autoPlayDelay.toFixed(1)}s</Text>
          </View>
          <Slider style={styles.slider}
            minimumValue={0.5} maximumValue={5.0} step={0.1}
            value={autoPlayDelay} onValueChange={handleAutoPlayDelayChange}
            minimumTrackTintColor={C.neonGreen} maximumTrackTintColor={C.cardBorder}
            thumbTintColor={C.neonGreen} />
          <View style={styles.sliderEnds}>
            <Text style={styles.sliderEnd}>Fast  0.5s</Text>
            <Text style={styles.sliderEnd}>Slow  5.0s</Text>
          </View>

          <TouchableOpacity
            style={[styles.fireBtn, !allReady && styles.fireBtnDisabled]}
            onPress={shootNow}
            disabled={!allReady}>
            <Text style={styles.fireBtnText}>▶  FIRE BEST SHOT</Text>
          </TouchableOpacity>
        </View>

        <SectionLabel text="TUNING" />

        {/* ── Tuning Card ── */}
        <View style={styles.card}>
          <View style={styles.sliderRow}>
            <Text style={styles.sliderLabel}>SHOT POWER</Text>
            <Text style={[styles.sliderValue, {color: C.gold}]}>{sensitivity.toFixed(1)}x</Text>
          </View>
          <Slider style={styles.slider}
            minimumValue={0.3} maximumValue={3.0} step={0.1}
            value={sensitivity} onValueChange={handleSensitivityChange}
            minimumTrackTintColor={C.gold} maximumTrackTintColor={C.cardBorder}
            thumbTintColor={C.gold} />
          <View style={styles.sliderEnds}>
            <Text style={styles.sliderEnd}>Soft</Text>
            <Text style={styles.sliderEnd}>Hard</Text>
          </View>

          <View style={[styles.divider, {marginTop: 12}]} />

          <View style={[styles.sliderRow, {marginTop: 12}]}>
            <Text style={styles.sliderLabel}>DETECTION SENSITIVITY</Text>
            <Text style={[styles.sliderValue, {color: C.neonBlue}]}>{detectThreshold}</Text>
          </View>
          <Text style={styles.sliderHint}>
            Lower = detects more  •  Raise to 35–45 to reduce false circles
          </Text>
          <Slider style={styles.slider}
            minimumValue={12} maximumValue={50} step={1}
            value={detectThreshold} onValueChange={handleThresholdChange}
            minimumTrackTintColor={C.neonBlue} maximumTrackTintColor={C.cardBorder}
            thumbTintColor={C.neonBlue} />
        </View>

        <SectionLabel text="HOW TO USE" />

        {/* ── Guide Card ── */}
        <View style={styles.card}>
          {[
            ['1', 'Grant "Draw over apps" — banner above'],
            ['2', 'Enable Accessibility (banner above) — bot needs this to swipe'],
            ['3', 'Toggle AIM OVERLAY on'],
            ['4', 'Toggle VISION ENGINE on (allow screen capture once)'],
            ['5', 'Turn on AUTO SHOOT BOT'],
            ['6', 'Switch to Carrom Pool — bot fires when board is stable'],
          ].map(([n, t]) => (
            <View key={n} style={styles.guideRow}>
              <View style={styles.guideBadge}><Text style={styles.guideBadgeText}>{n}</Text></View>
              <Text style={styles.guideText}>{t}</Text>
            </View>
          ))}
          <View style={styles.tipBox}>
            <Text style={styles.tipText}>
              Bot waits for a stable board before firing. Use FIRE BEST SHOT for a manual trigger anytime.
            </Text>
          </View>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerText}>CARROM BOT v11.0  •  Ghost-Ball AI  •  Press+Slide Engine</Text>
          <Text style={styles.footerSub}>4-strategy gesture engine  •  physics minimax AI</Text>
        </View>
      </ScrollView>
    </View>
  );
}

function StatusItem({label, on}: {label: string; on: boolean}) {
  return (
    <View style={styles.statusItem}>
      <StatusDot on={on} />
      <Text style={[styles.statusLabel, {color: on ? C.textPrimary : C.textDim}]}>{label}</Text>
    </View>
  );
}

function ToggleRow({label, icon, desc, value, onToggle, activeColor}:
  {label:string; icon:string; desc:string; value:boolean; onToggle:()=>void; activeColor:string}) {
  return (
    <View style={styles.toggleRow}>
      <View style={[styles.toggleIcon, {borderColor: value ? activeColor : C.cardBorder}]}>
        <Text style={[styles.toggleIconText, {color: value ? activeColor : C.textDim}]}>{icon}</Text>
      </View>
      <View style={styles.toggleInfo}>
        <Text style={[styles.toggleLabel, {color: value ? activeColor : C.textPrimary}]}>{label}</Text>
        <Text style={styles.toggleDesc}>{desc}</Text>
      </View>
      <Switch value={value} onValueChange={onToggle}
        trackColor={{false: C.cardBorder, true: activeColor + '55'}}
        thumbColor={value ? activeColor : '#445'} />
    </View>
  );
}

const styles = StyleSheet.create({
  root:         {flex: 1, backgroundColor: C.bg},

  // Header
  header:       {
    paddingTop: Platform.OS === 'android' ? (StatusBar.currentHeight ?? 24) + 6 : 44,
    paddingBottom: 14, paddingHorizontal: 18,
    backgroundColor: C.headerBg,
    borderBottomWidth: 1, borderBottomColor: C.cardBorder,
  },
  headerTop:    {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12},
  logo:         {color: C.textPrimary, fontSize: 28, fontWeight: '900', letterSpacing: 2},
  logoAccent:   {color: C.neonBlue},
  subtitle:     {color: C.textDim, fontSize: 11, letterSpacing: 1, marginTop: 2},
  liveBadge:    {flexDirection: 'row', alignItems: 'center', gap: 6,
    borderWidth: 1, borderColor: C.cardBorder, borderRadius: 20,
    paddingHorizontal: 10, paddingVertical: 5},
  liveDot:      {width: 7, height: 7, borderRadius: 4},
  liveText:     {fontSize: 11, fontWeight: '700', letterSpacing: 1},

  // Status bar
  statusBar:    {flexDirection: 'row', justifyContent: 'space-between'},
  statusItem:   {flexDirection: 'row', alignItems: 'center', gap: 5},
  statusLabel:  {fontSize: 10, fontWeight: '600', letterSpacing: 0.5},
  dot:          {
    width: 8, height: 8, borderRadius: 4,
    shadowOffset: {width: 0, height: 0}, shadowOpacity: 0.9, shadowRadius: 4, elevation: 3,
  },

  // Layout
  scroll:        {flex: 1},
  scrollContent: {padding: 14, paddingBottom: 50},

  // Alert banners
  alertBanner:  {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    backgroundColor: '#1A0D00', borderWidth: 1, borderColor: C.neonOrange,
    borderRadius: 10, padding: 12, marginBottom: 10,
  },
  accessBanner: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    backgroundColor: '#0A0A20', borderWidth: 1, borderColor: C.neonBlue,
    borderRadius: 10, padding: 12, marginBottom: 10,
  },
  alertIcon:    {fontSize: 20, color: C.gold},
  alertTitle:   {color: C.neonOrange, fontSize: 13, fontWeight: '700'},
  accessTitle:  {color: C.neonBlue, fontSize: 13, fontWeight: '700'},
  alertSub:     {color: C.textDim, fontSize: 11, marginTop: 2},
  alertArrow:   {color: C.textDim, fontSize: 22},

  // Section label
  sectionLabelRow: {flexDirection: 'row', alignItems: 'center', marginVertical: 14, gap: 10},
  sectionLabelLine:{flex: 1, height: 1, backgroundColor: C.cardBorder},
  sectionLabelText:{color: C.textDim, fontSize: 11, fontWeight: '700', letterSpacing: 1.5},

  // Cards
  card:         {
    backgroundColor: C.card, borderRadius: 14, padding: 16,
    marginBottom: 10, borderWidth: 1, borderColor: C.cardBorder,
  },
  cardActive:   {borderColor: C.neonGreen + '66'},
  divider:      {height: 1, backgroundColor: C.cardBorder, marginVertical: 12},

  // Toggle rows
  toggleRow:    {flexDirection: 'row', alignItems: 'center', gap: 12},
  toggleIcon:   {
    width: 38, height: 38, borderRadius: 10, borderWidth: 1,
    alignItems: 'center', justifyContent: 'center',
  },
  toggleIconText:{fontSize: 18},
  toggleInfo:   {flex: 1},
  toggleLabel:  {fontSize: 13, fontWeight: '700', letterSpacing: 0.5},
  toggleDesc:   {color: C.textDim, fontSize: 11, marginTop: 2},

  // Sliders
  sliderRow:    {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  sliderLabel:  {color: C.textPrimary, fontSize: 12, fontWeight: '700', letterSpacing: 0.5},
  sliderValue:  {fontSize: 18, fontWeight: '900'},
  sliderHint:   {color: C.textDim, fontSize: 11, marginTop: 2, marginBottom: 4},
  slider:       {width: '100%', height: 36},
  sliderEnds:   {flexDirection: 'row', justifyContent: 'space-between'},
  sliderEnd:    {color: C.textDim, fontSize: 10},

  // Fire button
  fireBtn:      {
    marginTop: 14, paddingVertical: 14, borderRadius: 10,
    backgroundColor: '#001A0D', alignItems: 'center',
    borderWidth: 1.5, borderColor: C.neonGreen,
  },
  fireBtnDisabled:{opacity: 0.3},
  fireBtnText:  {color: C.neonGreen, fontSize: 15, fontWeight: '900', letterSpacing: 1},

  // Guide
  guideRow:     {flexDirection: 'row', alignItems: 'flex-start', gap: 10, marginBottom: 10},
  guideBadge:   {
    width: 22, height: 22, borderRadius: 6, backgroundColor: C.neonBlue + '22',
    borderWidth: 1, borderColor: C.neonBlue, alignItems: 'center', justifyContent: 'center',
  },
  guideBadgeText:{color: C.neonBlue, fontSize: 11, fontWeight: '700'},
  guideText:    {color: C.textPrimary, fontSize: 13, flex: 1, lineHeight: 19},
  tipBox:       {
    backgroundColor: C.gold + '14', borderWidth: 1, borderColor: C.gold + '44',
    borderRadius: 8, padding: 10, marginTop: 6,
  },
  tipText:      {color: C.gold, fontSize: 12, lineHeight: 18},

  // Footer
  footer:       {alignItems: 'center', marginTop: 16},
  footerText:   {color: C.textDim, fontSize: 11, letterSpacing: 0.5},
  footerSub:    {color: C.textDim + '88', fontSize: 10, marginTop: 4},
});
