/**
 * CarromBot — v12.0 CHEAT ENGINE
 * Full eSports UI — Engine-first design
 */

import React, {useState, useEffect, useCallback} from 'react';
import {
  View, Text, StyleSheet, TouchableOpacity, Alert,
  Switch, ScrollView, Platform, StatusBar, NativeModules, Linking,
} from 'react-native';
import Slider from '@react-native-community/slider';

const {OverlayModule} = NativeModules;

// ── eSports palette ───────────────────────────────────────────────────────────
const C = {
  bg:       '#04070F',
  bgCard:   '#080E1C',
  border:   '#152038',
  borderHi: '#1E3A60',
  neon:     '#00D4FF',
  green:    '#00FF87',
  red:      '#FF3355',
  orange:   '#FF6B35',
  gold:     '#FFD700',
  purple:   '#9B59FF',
  text:     '#D8EEFF',
  dim:      '#4A6080',
  headerBg: '#060C1A',
};

// ── Mini components ───────────────────────────────────────────────────────────
const LED = ({on, color}: {on: boolean; color?: string}) => (
  <View style={[styles.led, {backgroundColor: on ? (color || C.green) : C.red,
    shadowColor: on ? (color || C.green) : C.red}]} />
);

const Divider = () => <View style={styles.divider} />;

const SectionTitle = ({text}: {text: string}) => (
  <View style={styles.secRow}>
    <View style={styles.secLine}/>
    <Text style={styles.secText}>{text}</Text>
    <View style={styles.secLine}/>
  </View>
);

const StatusPill = ({label, on, color}: {label:string;on:boolean;color?:string}) => (
  <View style={styles.pill}>
    <LED on={on} color={color}/>
    <Text style={[styles.pillText, {color: on ? C.text : C.dim}]}>{label}</Text>
  </View>
);

// ── Main App ──────────────────────────────────────────────────────────────────
export default function App() {
  const [hasOverlay, setHasOverlay]       = useState(false);
  const [overlayActive, setOverlayActive] = useState(false);
  const [autoDetect, setAutoDetect]       = useState(false);
  const [sensitivity, setSensitivity]     = useState(1.0);
  const [detectThr, setDetectThr]         = useState(36);
  const [autoPlay, setAutoPlay]           = useState(false);
  const [delay, setDelay]                 = useState(2.0);
  const [accReady, setAccReady]           = useState(false);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 2000);
    return () => clearInterval(t);
  }, []);

  const refresh = useCallback(async () => {
    try { setHasOverlay(await OverlayModule.canDrawOverlays()); } catch { setHasOverlay(true); }
    try { setAutoDetect(await OverlayModule.isAutoDetectActive()); } catch {}
    try { setAccReady(await OverlayModule.isAccessibilityReady()); } catch { setAccReady(false); }
    try { setAutoPlay(await OverlayModule.isAutoPlayEnabled()); } catch {}
  }, []);

  const requestOverlay = useCallback(() => {
    try { OverlayModule.requestOverlayPermission(); setTimeout(refresh, 1200); }
    catch { Alert.alert('Permission', 'Grant "Display over other apps" in Settings.',
      [{text:'Open', onPress:()=>Linking.openSettings()}]); }
  }, [refresh]);

  const toggleOverlay = useCallback(async () => {
    if (!hasOverlay) { requestOverlay(); return; }
    try {
      if (overlayActive) {
        if (autoPlay) { try{await OverlayModule.setAutoPlay(false);}catch{} setAutoPlay(false); }
        await OverlayModule.stopOverlay();
        setOverlayActive(false); setAutoDetect(false);
      } else {
        await OverlayModule.startOverlay();
        setOverlayActive(true);
      }
    } catch (e:any) { Alert.alert('Error', e.message); }
  }, [hasOverlay, overlayActive, autoPlay, requestOverlay]);

  const toggleDetect = useCallback(async () => {
    if (!overlayActive) { Alert.alert('Start Overlay First','Enable Overlay before Vision Engine.'); return; }
    try {
      if (autoDetect) { await OverlayModule.stopScreenCapture(); setAutoDetect(false); }
      else { await OverlayModule.requestScreenCapture(); setTimeout(refresh, 2500); }
    } catch (e:any) { Alert.alert('Error', e.message); }
  }, [overlayActive, autoDetect, refresh]);

  const toggleBot = useCallback(async () => {
    if (!overlayActive || !autoDetect) {
      Alert.alert('Engine Not Ready', 'Enable Overlay + Vision Engine first.'); return; }
    if (!accReady && !autoPlay) {
      Alert.alert('Accessibility Required',
        'Enable "AIMxASSIST" in Android Accessibility Settings to let the bot swipe.',
        [{text:'Open Accessibility', onPress:()=>OverlayModule.requestAccessibilityPermission()},
         {text:'Cancel', style:'cancel'}]); return; }
    try {
      const next = !autoPlay;
      await OverlayModule.setAutoPlay(next);
      setAutoPlay(next);
      if (next) Alert.alert('ENGINE ACTIVATED',
        'The engine will auto-launch Carrom Disc Pool and start playing.\n\nSwitch to the game now!',
        [{text:'GO!'}]);
    } catch (e:any) {
      if (e.code==='ERR_NO_ACCESSIBILITY')
        Alert.alert('Accessibility Not Ready','Enable "AIMxASSIST" in Accessibility Settings.',
          [{text:'Open', onPress:()=>OverlayModule.requestAccessibilityPermission()}]);
      else Alert.alert('Error', e.message);
    }
  }, [overlayActive, autoDetect, accReady, autoPlay]);

  const fireNow = useCallback(async () => {
    if (!overlayActive || !autoDetect || !accReady) {
      Alert.alert('Not Ready', 'All three systems must be active (Overlay + Vision + Accessibility).'); return; }
    try { await OverlayModule.shootNow(); }
    catch (e:any) { Alert.alert('Shot Failed', e.message || 'Ensure you are in-game on the carrom board'); }
  }, [overlayActive, autoDetect, accReady]);

  const allReady = overlayActive && autoDetect && accReady;

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor={C.bg}/>

      {/* ── Header ── */}
      <View style={styles.header}>
        <View style={styles.headerRow}>
          <View>
            <Text style={styles.logo}>CARROM<Text style={styles.logoBlue}> ENGINE</Text></Text>
            <Text style={styles.sub}>Cheat Engine  •  v12.0  •  Forward-Drag Bot</Text>
          </View>
          <View style={[styles.badgeWrap, {borderColor: allReady ? C.green : C.dim}]}>
            <LED on={allReady} color={C.green}/>
            <Text style={[styles.badgeText, {color: allReady ? C.green : C.dim}]}>
              {allReady ? 'LIVE' : 'IDLE'}
            </Text>
          </View>
        </View>

        {/* Status row */}
        <View style={styles.statusRow}>
          <StatusPill label="OVERLAY"  on={overlayActive}/>
          <StatusPill label="VISION"   on={autoDetect}/>
          <StatusPill label="ACCESS"   on={accReady}/>
          <StatusPill label="ENGINE"   on={autoPlay} color={C.gold}/>
        </View>
      </View>

      <ScrollView style={styles.scroll} contentContainerStyle={styles.content}
        showsVerticalScrollIndicator={false}>

        {/* ── Permission banners ── */}
        {!hasOverlay && (
          <TouchableOpacity style={[styles.banner, {borderColor:C.orange}]} onPress={requestOverlay}>
            <Text style={[styles.bannerIcon, {color:C.orange}]}>!</Text>
            <View style={{flex:1}}>
              <Text style={[styles.bannerTitle, {color:C.orange}]}>Overlay Permission Required</Text>
              <Text style={styles.bannerSub}>Tap → Grant "Display over other apps"</Text>
            </View>
          </TouchableOpacity>
        )}
        {!accReady && (
          <TouchableOpacity style={[styles.banner, {borderColor:C.neon}]}
            onPress={()=>OverlayModule.requestAccessibilityPermission()}>
            <Text style={[styles.bannerIcon, {color:C.neon}]}>⚡</Text>
            <View style={{flex:1}}>
              <Text style={[styles.bannerTitle, {color:C.neon}]}>Accessibility Required for Bot</Text>
              <Text style={styles.bannerSub}>Settings → Accessibility → AIMxASSIST → ON</Text>
            </View>
          </TouchableOpacity>
        )}

        <SectionTitle text="ENGINE SYSTEMS"/>

        {/* ── System controls ── */}
        <View style={styles.card}>
          <SysRow
            icon="◈" label="AIM OVERLAY"
            desc={overlayActive ? 'Overlay active on game screen' : 'Start overlay engine'}
            value={overlayActive} onToggle={toggleOverlay} color={C.gold}/>
          <Divider/>
          <SysRow
            icon="◉" label="VISION ENGINE"
            desc={autoDetect ? 'Screen reader active — detecting game state' : 'Real-time screen analysis (one-time permission)'}
            value={autoDetect} onToggle={toggleDetect} color={C.neon}/>
        </View>

        <SectionTitle text="BOT ENGINE"/>

        {/* ── Bot card ── */}
        <View style={[styles.card, autoPlay && {borderColor:C.green+'55'}]}>
          <SysRow
            icon="⚡" label="CHEAT ENGINE BOT"
            desc={autoPlay
              ? 'ENGINE RUNNING — switch to Carrom Disc Pool now!'
              : 'Bot auto-opens the game and plays for you'}
            value={autoPlay} onToggle={toggleBot} color={C.green}/>

          {autoPlay && (
            <View style={styles.engineStatus}>
              <Text style={styles.engineStatusText}>
                Engine auto-launched Carrom Disc Pool  •  Detecting board...
              </Text>
              <Text style={styles.engineStatusSub}>
                Bot fires when the board is stable (6 frames). Forward-drag gesture active.
              </Text>
            </View>
          )}

          <Divider/>

          <View style={styles.sliderRow}>
            <Text style={styles.sliderLabel}>FIRE DELAY</Text>
            <Text style={[styles.sliderVal, {color:C.green}]}>{delay.toFixed(1)}s</Text>
          </View>
          <Slider style={styles.slider}
            minimumValue={0.5} maximumValue={5.0} step={0.1} value={delay}
            onValueChange={async v => {
              setDelay(v);
              try{await OverlayModule.setAutoPlayDelay(Math.round(v*1000));}catch{}
            }}
            minimumTrackTintColor={C.green} maximumTrackTintColor={C.border}
            thumbTintColor={C.green}/>
          <View style={styles.ends}>
            <Text style={styles.endLabel}>Fast  0.5s</Text>
            <Text style={styles.endLabel}>Slow  5.0s</Text>
          </View>

          {/* Fire button */}
          <TouchableOpacity
            style={[styles.fireBtn, !allReady && styles.fireBtnOff]}
            onPress={fireNow} disabled={!allReady}>
            <Text style={[styles.fireBtnText, !allReady && {color:C.dim}]}>
              {allReady ? '▶  FIRE BEST SHOT NOW' : 'Start all systems above first'}
            </Text>
          </TouchableOpacity>
        </View>

        <SectionTitle text="TUNING"/>

        {/* ── Tuning ── */}
        <View style={styles.card}>
          <View style={styles.sliderRow}>
            <Text style={styles.sliderLabel}>SHOT POWER</Text>
            <Text style={[styles.sliderVal, {color:C.gold}]}>{sensitivity.toFixed(1)}x</Text>
          </View>
          <Slider style={styles.slider}
            minimumValue={0.3} maximumValue={3.0} step={0.1} value={sensitivity}
            onValueChange={v=>{setSensitivity(v);try{OverlayModule.setSensitivity(v);}catch{}}}
            minimumTrackTintColor={C.gold} maximumTrackTintColor={C.border}
            thumbTintColor={C.gold}/>
          <View style={styles.ends}>
            <Text style={styles.endLabel}>Soft</Text>
            <Text style={styles.endLabel}>Hard</Text>
          </View>
          <Divider/>
          <View style={[styles.sliderRow, {marginTop:10}]}>
            <Text style={styles.sliderLabel}>DETECTION SENSITIVITY</Text>
            <Text style={[styles.sliderVal, {color:C.neon}]}>{detectThr}</Text>
          </View>
          <Text style={styles.hint}>Lower = more circles (may ghost). Raise to 35–45 for less noise.</Text>
          <Slider style={styles.slider}
            minimumValue={12} maximumValue={50} step={1} value={detectThr}
            onValueChange={v=>{setDetectThr(v);try{OverlayModule.setDetectionThreshold(v);}catch{}}}
            minimumTrackTintColor={C.neon} maximumTrackTintColor={C.border}
            thumbTintColor={C.neon}/>
        </View>

        <SectionTitle text="HOW TO USE"/>

        {/* ── Guide ── */}
        <View style={styles.card}>
          {[
            ['1','Grant Overlay permission — banner above'],
            ['2','Enable Accessibility — banner above\n    Settings → Accessibility → AIMxASSIST → ON'],
            ['3','Toggle AIM OVERLAY on'],
            ['4','Toggle VISION ENGINE on (allow screen capture once)'],
            ['5','Turn on CHEAT ENGINE BOT'],
            ['6','Engine auto-opens Carrom Disc Pool!\n    Bot detects board and fires automatically'],
            ['7','Use floating button while in-game:\n    Tap = open control panel  •  PAUSE/RUN the bot'],
          ].map(([n,t])=>(
            <View key={n} style={styles.step}>
              <View style={styles.stepBadge}>
                <Text style={styles.stepNum}>{n}</Text>
              </View>
              <Text style={styles.stepText}>{t}</Text>
            </View>
          ))}

          <View style={styles.tipBox}>
            <Text style={styles.tipTitle}>HOW THE BOT WORKS</Text>
            <Text style={styles.tipText}>
              {'\u2022'} Engine opens Carrom Disc Pool automatically{'\n'}
              {'\u2022'} Vision reads screen 30×/sec — detects striker, coins, pockets{'\n'}
              {'\u2022'} Physics AI computes the optimal shot (ghost-ball + minimax){'\n'}
              {'\u2022'} Bot uses forward-drag gesture — touches striker and drags toward target{'\n'}
              {'\u2022'} Fires only when board is stable (6 frames){'\n'}
              {'\u2022'} Cooldown between shots: configurable above
            </Text>
          </View>

          <View style={[styles.tipBox, {borderColor:C.neon+'44', backgroundColor:C.neon+'0A', marginTop:8}]}>
            <Text style={[styles.tipTitle, {color:C.neon}]}>GESTURE FIX v12</Text>
            <Text style={[styles.tipText, {color:C.neon+'CC'}]}>
              Previous versions swiped BACKWARD (wrong). v12 now drags FORWARD toward the target — exactly how Carrom Disc Pool's drag mechanic works.{'\n\n'}
              Strategy: hold 80ms → smooth bezier slide 260ms toward coins.
            </Text>
          </View>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerText}>CARROM ENGINE v12.0</Text>
          <Text style={styles.footerSub}>Forward-Drag  •  Physics AI  •  Ghost-Ball  •  Auto-Launch</Text>
        </View>
      </ScrollView>
    </View>
  );
}

function SysRow({icon, label, desc, value, onToggle, color}:
  {icon:string;label:string;desc:string;value:boolean;onToggle:()=>void;color:string}) {
  return (
    <View style={styles.sysRow}>
      <View style={[styles.sysIcon, {borderColor: value ? color : C.border}]}>
        <Text style={[styles.sysIconText, {color: value ? color : C.dim}]}>{icon}</Text>
      </View>
      <View style={styles.sysInfo}>
        <Text style={[styles.sysLabel, {color: value ? color : C.text}]}>{label}</Text>
        <Text style={styles.sysDesc}>{desc}</Text>
      </View>
      <Switch value={value} onValueChange={onToggle}
        trackColor={{false:C.border, true:color+'44'}}
        thumbColor={value ? color : '#334'}/>
    </View>
  );
}

const styles = StyleSheet.create({
  root:    {flex:1, backgroundColor:C.bg},

  // Header
  header:     {
    paddingTop: Platform.OS==='android' ? (StatusBar.currentHeight??24)+6 : 44,
    paddingBottom:14, paddingHorizontal:18,
    backgroundColor:C.headerBg,
    borderBottomWidth:1, borderBottomColor:C.border,
  },
  headerRow:  {flexDirection:'row', justifyContent:'space-between', alignItems:'center', marginBottom:14},
  logo:       {color:C.text, fontSize:27, fontWeight:'900', letterSpacing:2},
  logoBlue:   {color:C.neon},
  sub:        {color:C.dim, fontSize:11, letterSpacing:1, marginTop:3},
  badgeWrap:  {flexDirection:'row', alignItems:'center', gap:6, borderWidth:1,
    borderRadius:20, paddingHorizontal:10, paddingVertical:5},
  badgeText:  {fontSize:11, fontWeight:'700', letterSpacing:1},

  // Status row
  statusRow:  {flexDirection:'row', justifyContent:'space-between'},
  pill:       {flexDirection:'row', alignItems:'center', gap:5},
  pillText:   {fontSize:10, fontWeight:'600', letterSpacing:0.4},

  // LED dot
  led:        {width:8, height:8, borderRadius:4,
    shadowOffset:{width:0,height:0}, shadowOpacity:0.9, shadowRadius:5, elevation:4},

  // Layout
  scroll:   {flex:1},
  content:  {padding:14, paddingBottom:50},

  // Banners
  banner:   {flexDirection:'row', alignItems:'center', gap:10,
    backgroundColor:'#0A0A0A', borderWidth:1, borderRadius:10,
    padding:12, marginBottom:10},
  bannerIcon:  {fontSize:20},
  bannerTitle: {fontSize:13, fontWeight:'700'},
  bannerSub:   {color:C.dim, fontSize:11, marginTop:2},

  // Section title
  secRow:  {flexDirection:'row', alignItems:'center', marginVertical:14, gap:10},
  secLine: {flex:1, height:1, backgroundColor:C.border},
  secText: {color:C.dim, fontSize:11, fontWeight:'700', letterSpacing:1.5},

  // Cards
  card:    {backgroundColor:C.bgCard, borderRadius:14, padding:16,
    marginBottom:10, borderWidth:1, borderColor:C.border},
  divider: {height:1, backgroundColor:C.border, marginVertical:12},

  // System toggle rows
  sysRow:      {flexDirection:'row', alignItems:'center', gap:12},
  sysIcon:     {width:40, height:40, borderRadius:10, borderWidth:1,
    alignItems:'center', justifyContent:'center'},
  sysIconText: {fontSize:18},
  sysInfo:     {flex:1},
  sysLabel:    {fontSize:13, fontWeight:'700', letterSpacing:0.4},
  sysDesc:     {color:C.dim, fontSize:11, marginTop:2},

  // Engine status panel
  engineStatus:    {
    marginTop:10, backgroundColor:'#001809', borderRadius:8,
    borderWidth:1, borderColor:C.green+'33', padding:10,
  },
  engineStatusText: {color:C.green, fontSize:11, fontWeight:'700'},
  engineStatusSub:  {color:C.green+'99', fontSize:10, marginTop:3},

  // Sliders
  sliderRow: {flexDirection:'row', justifyContent:'space-between', alignItems:'center'},
  sliderLabel:{color:C.text, fontSize:12, fontWeight:'700', letterSpacing:0.4},
  sliderVal: {fontSize:18, fontWeight:'900'},
  hint:      {color:C.dim, fontSize:11, marginTop:2, marginBottom:2},
  slider:    {width:'100%', height:36},
  ends:      {flexDirection:'row', justifyContent:'space-between'},
  endLabel:  {color:C.dim, fontSize:10},

  // Fire button
  fireBtn:     {
    marginTop:14, paddingVertical:14, borderRadius:10,
    backgroundColor:'#001A0D', borderWidth:1.5, borderColor:C.green,
    alignItems:'center',
  },
  fireBtnOff:  {backgroundColor:'#0A0A0A', borderColor:C.border},
  fireBtnText: {color:C.green, fontSize:15, fontWeight:'900', letterSpacing:0.8},

  // Guide
  step:       {flexDirection:'row', alignItems:'flex-start', gap:10, marginBottom:12},
  stepBadge:  {width:24, height:24, borderRadius:6, backgroundColor:C.neon+'18',
    borderWidth:1, borderColor:C.neon, alignItems:'center', justifyContent:'center'},
  stepNum:    {color:C.neon, fontSize:11, fontWeight:'700'},
  stepText:   {color:C.text, fontSize:13, flex:1, lineHeight:20},
  tipBox:     {backgroundColor:C.gold+'0F', borderWidth:1, borderColor:C.gold+'44',
    borderRadius:8, padding:12, marginTop:4},
  tipTitle:   {color:C.gold, fontSize:11, fontWeight:'900', letterSpacing:1, marginBottom:6},
  tipText:    {color:C.gold+'CC', fontSize:12, lineHeight:19},

  // Footer
  footer:     {alignItems:'center', marginTop:16},
  footerText: {color:C.dim, fontSize:12, letterSpacing:0.5, fontWeight:'700'},
  footerSub:  {color:C.dim+'88', fontSize:10, marginTop:4},
});
