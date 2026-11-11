import subprocess, os
U='/root/.claude/uploads/14321095-c159-520e-8a2f-27067bac8c4f/'
C=dict(H='6a6f5d76-289371963_1794434712287587.mp4', W='51535b24-615721791_1794434713441535.mp4',
 D1='07d43c73-242913137_1794434715381164.mp4', B='884593ea-469972184_1794434716384943.mp4',
 D2='2085e9ae-287824351_1794434718468318.mp4', G='f58bb004-265402145_1794434719941722.mp4',
 K='deb262a1-599444070_1794434722487587.mp4', D3='80fcdbdb-34721038_1794434726038451.mp4',
 S='8ddcc548-808969256_1794434729588513.mp4')
bp=0.5502; bar=4*bp; P=16*bp; t0=2.12+P   # intro ends here (10.92)
END=144.79
segs=[]  # (clip, src_start, src_end, out_dur, zoom)
def add(c,a,b,d,z=1.0,interp=False): segs.append((c,a,b,d,z,interp))
add('H',0,6.03,t0,interp=True)                     # intro
def verse(c): add(c,0,10.0,P)
def chorus(c):
    add(c,0,10.0,P*1.5)                            # dance, gentle slow-mo
    add(c,5.6,10.0,P*0.5,z=1.35)                   # close-up on the cheer
verse('W'); chorus('D1')
verse('B'); chorus('D2')
verse('G'); chorus('D3')
verse('K'); verse('S')
# final chorus montage: 8 bars
for c,a in [('D1',0.5),('D2',0.5),('D3',1.0),('B',7.0),('D1',7.5),('D2',7.5),('G',7.5),('D3',7.6)]:
    add(c,a,a+bar,bar)
used=t0+sum(s[3] for s in segs[1:])
add('S',5.0,10.0,6.6)                               # waving at the gate
add('H',0,6.03,END-used-6.6,interp=True)            # bookend house shot
lst=open('list.txt','w')
for i,(c,a,b,d,z,interp) in enumerate(segs):
    k=d/(b-a)
    vf=f"trim={a}:{b},setpts=(PTS-STARTPTS)*{k:.6f}"
    if z>1: vf+=f",scale={int(1280*z)}:-2,crop=1280:720:(iw-1280)/2:(ih-720)/3"
    vf+=",minterpolate=fps=24:mi_mode=mci:mc_mode=aobmc:vsbmc=1" if interp else ",fps=24"
    vf+=f",trim=duration={d:.4f},format=yuv420p"
    out=f'seg/{i:02d}.mp4'
    subprocess.run(['ffmpeg','-v','error','-y','-i',U+C[c],'-an','-vf',vf,'-c:v','libx264','-crf','16','-preset','medium','-r','24',out],check=True)
    lst.write(f"file '{out}'\n"); print(i,c,round(d,2),round(k,2))
lst.close()
