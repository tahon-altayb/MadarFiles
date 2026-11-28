"""Bounded per-shot tracking. A track's verdict covers its whole length; its own clear looks cover nearby frames too.
Unknown close people stay covered; far, faceless or lifeless figures need their own looks to be covered."""
import math

def iou(a, b):
    area = max(0, min(a[2],b[2])-max(a[0],b[0])) * max(0, min(a[3],b[3])-max(a[1],b[1]))
    return area/max(1e-9, (a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-area)

LOOKS = 9
MALE_LOOK = .20
FEMALE_LOOK = .80
EDGE = .02          # box top this close to the frame top: the head is cut off
PARTIAL_LOOKS = 2
APPEARANCE_SPLIT = .55   # colour distance above which a box is not the same person (an undetected cut)
LOCAL_LOOKS = 3          # looks near a frame needed for a local verdict
BODY_MEAN = .60          # a body seen without a face counts for the group only with this average...
STRONG_BODY = .90        # ...or, against a face that disagrees, with several looks this clear
STRONG_LOOKS = 4
STILL_LEVEL = .5         # grey levels: a person never stays this still (armour, statues, mannequins)
STILL_SAMPLES = 10
NEAR_HEIGHT = .30   # a body this tall (share of the frame) is close enough to judge without a face


class Track:
    def __init__(self, number, box, frame):
        self.number, self.box, self.last = number, list(box), frame
        self.velocity = [0.0]*4
        self.looks = []
        self.faced = 0      # looks that had this person's own face
        self.headless = 0   # looks with the body cut by the top of the frame (head out of the picture)
        self.classified_at = -1000
        self.history = []   # (frame, look, face, near) for every look of the track
        self.appearance = None
        self.stirs = []     # inner movement while the camera holds still

    def predict(self, frame):
        step = min(4, max(0, frame-self.last))
        b = [min(1.0,max(0.0,x+v*step)) for x,v in zip(self.box,self.velocity)]
        return b if b[2]>b[0] and b[3]>b[1] else self.box

    def observe(self, box, frame):
        gap=max(1, frame-self.last)
        self.velocity=[.5*v+.5*(b-a)/gap for a,b,v in zip(self.box,box,self.velocity)]
        self.box, self.last = list(box), frame

    def stir(self, level):
        self.stirs=(self.stirs+[level])[-60:]

    @property
    def lifeless(self):
        """Armour, a statue or a mannequin: detected as a person but never moving while the camera holds."""
        if self.faced or len(self.stirs)<STILL_SAMPLES: return False
        ordered=sorted(self.stirs);return ordered[len(ordered)//2]<STILL_LEVEL

    def vote(self, female_probability, frame, face=True, near=True):
        if not math.isfinite(female_probability) or not 0<=female_probability<=1:
            raise ValueError('CENSOR_MODEL_OUTPUT')
        self.classified_at=frame
        self.looks=(self.looks+[female_probability])[-LOOKS:]
        self.history.append((frame,female_probability,bool(face),bool(face) or bool(near)))
        if face: self.faced+=1
        elif self.box[1]<=EDGE: self.headless+=1

    @property
    def partial(self):
        """Only legs or a torso: the head is outside the picture and no face was ever seen."""
        return self.faced==0 and len(self.looks)>=PARTIAL_LOOKS and self.headless*2>=len(self.looks)

    # One look used to decide for good: a man whose box held the interviewer's face once stayed covered.
    # Now the recent looks vote. Anyone not yet clearly the other group (two clear looks, a majority,
    # and the average on that side) stays covered.
    @property
    def male_votes(self): return sum(p<=MALE_LOOK for p in self.looks)
    @property
    def female_votes(self): return sum(p>=FEMALE_LOOK for p in self.looks)
    @property
    def mean(self): return sum(self.looks)/len(self.looks) if self.looks else .5
    @property
    def female(self): return bool(self.looks) and self.mean>=.5
    @property
    def male(self): return bool(self.looks) and self.mean<.5

    def covered(self, target='female'):
        """Anyone close enough to judge and not yet clearly the other group stays covered. Far figures
        (crowds, riders on a hill), lifeless figures and bodies without a face are the OTHER group until
        their own looks show the target: a woman in a crowd is covered once she comes close enough."""
        if self.lifeless: return False
        judged=[(p,face) for _,p,face,near in self.history if near]
        close=self.box[3]-self.box[1]>=NEAR_HEIGHT
        if not judged: return close   # someone close and not yet seen stays covered
        faces=[p for p,face in judged if face][-LOOKS:];bodies=[p for p,face in judged if not face]
        clear=lambda p: p>=FEMALE_LOOK if target=='female' else p<=MALE_LOOK
        other=lambda p: p<=MALE_LOOK if target=='female' else p>=FEMALE_LOOK
        strong=lambda p: p>=STRONG_BODY if target=='female' else p<=1-STRONG_BODY
        # A body clearly of the target group, again and again, outweighs a face that may be a neighbour's.
        if sum(strong(p) for p in bodies)>=STRONG_LOOKS: return True
        if faces:
            looks=[p for p,_ in judged][-LOOKS:];mean=sum(looks)/len(looks)
            # Uncovered only with at least two more clear looks for the other group than for this one.
            return not (sum(other(p) for p in looks)>=sum(clear(p) for p in looks)+2 and (mean<=.45 if target=='female' else mean>=.55))
        looks=bodies[-LOOKS:]
        if len(looks)<2: return close
        mean=sum(looks)/len(looks)
        on_side=mean>=BODY_MEAN if target=='female' else mean<=1-BODY_MEAN
        return on_side and sum(clear(p) for p in looks)>=sum(other(p) for p in looks)+1

    def clearly(self, target, frame, reach):
        """The looks around this frame clearly show the target group. A track that silently ran across an
        undetected cut (a man's track continuing on a woman's close-up) took the verdict of its last
        person for its whole length; the woman's own part is now covered by her own looks."""
        if self.lifeless: return False
        near=[(p,face) for f,p,face,judged in self.history if judged and abs(f-frame)<=reach]
        strong=sum((p>=STRONG_BODY if target=='female' else p<=1-STRONG_BODY) for p,face in near if not face)
        if strong>=LOCAL_LOOKS: return True
        near=[p for p,_ in near]
        if len(near)<LOCAL_LOOKS: return False
        female=sum(p>=FEMALE_LOOK for p in near);male=sum(p<=MALE_LOOK for p in near);mean=sum(near)/len(near)
        if target=='male': return male>=female+2 and mean<=.45
        return female>=male+2 and mean>=.55

    def covered_at(self, target, frame, reach):
        return self.covered(target) or self.clearly(target,frame,reach)

class Tracker:
    def __init__(self, coast_frames=5, distance=None, hold_frames=None):
        self.active, self.all, self.next = [], {}, 0
        # Cuts are now caught reliably, so the old place stays covered only briefly (it showed over the next man).
        self.hold_frames = coast_frames if hold_frames is None else max(1,hold_frames)
        self.distance = distance
        self.held = []
        self.coast_frames = max(1,min(30,coast_frames))

    def update(self, boxes, frame, cut=False, appearances=None):
        # A cut (or a wipe/fade between shots) used to drop every track at once, so a woman the detector
        # missed during the transition was briefly uncovered. Her last place stays covered for the coast
        # time; held tracks are never matched to people in the new shot.
        if cut:
            self.held=self.held+[(t,frame) for t in self.active]
            self.active=[]
        self.held=[(t,since) for t,since in self.held if frame-since<=self.hold_frames]
        self.active=[t for t in self.active if frame-t.last<=self.coast_frames]
        pairs=[]
        for track in self.active:
            for index,box in enumerate(boxes):
                score=iou(track.predict(frame),box)
                if score<.15: continue
                if self.distance and appearances and appearances[index] is not None and track.appearance is not None \
                        and self.distance(track.appearance,appearances[index])>APPEARANCE_SPLIT: continue
                pairs.append((score,track.number,index))
        used_tracks,used_boxes,assigned=set(),set(),{}
        for score,number,index in sorted(pairs,reverse=True):
            if number in used_tracks or index in used_boxes: continue
            track=self.all[number];track.observe(boxes[index],frame)
            used_tracks.add(number);used_boxes.add(index);assigned[index]=track
        for index,box in enumerate(boxes):
            if index in used_boxes: continue
            track=Track(self.next,box,frame);self.next+=1
            self.active.append(track);self.all[track.number]=track;assigned[index]=track
            used_tracks.add(track.number)
        if appearances:
            for index,track in assigned.items():
                seen=appearances[index]
                if seen is None: continue
                track.appearance=seen if track.appearance is None else .7*track.appearance+.3*seen
        coast=[(t.number,t.predict(frame)) for t in self.active if t.number not in used_tracks]
        coast+=[(t.number,list(t.box)) for t,_ in self.held]
        return assigned,coast

def padded(box, amount=.10):
    x,y,r,b=box;dx=(r-x)*amount;dy=(b-y)*amount
    return [round(max(0,x-dx),6),round(max(0,y-dy),6),round(min(1,r+dx),6),round(min(1,b+dy),6)]

