"""Bounded per-shot tracking. Verdicts are applied to the WHOLE analysed track."""
import math

def iou(a, b):
    area = max(0, min(a[2],b[2])-max(a[0],b[0])) * max(0, min(a[3],b[3])-max(a[1],b[1]))
    return area/max(1e-9, (a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-area)

LOOKS = 9
MALE_LOOK = .20
FEMALE_LOOK = .80


class Track:
    def __init__(self, number, box, frame):
        self.number, self.box, self.last = number, list(box), frame
        self.velocity = [0.0]*4
        self.looks = []
        self.classified_at = -1000

    def predict(self, frame):
        step = min(4, max(0, frame-self.last))
        b = [min(1.0,max(0.0,x+v*step)) for x,v in zip(self.box,self.velocity)]
        return b if b[2]>b[0] and b[3]>b[1] else self.box

    def observe(self, box, frame):
        gap=max(1, frame-self.last)
        self.velocity=[.5*v+.5*(b-a)/gap for a,b,v in zip(self.box,box,self.velocity)]
        self.box, self.last = list(box), frame

    def vote(self, female_probability, frame):
        if not math.isfinite(female_probability) or not 0<=female_probability<=1:
            raise ValueError('CENSOR_MODEL_OUTPUT')
        self.classified_at=frame
        self.looks=(self.looks+[female_probability])[-LOOKS:]

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
        # Uncovered only with at least two more clear looks for the other group than for this one.
        if target=='male':
            return not (self.female_votes>=self.male_votes+2 and self.mean>=.55)
        return not (self.male_votes>=self.female_votes+2 and self.mean<=.45)

class Tracker:
    def __init__(self, coast_frames=5):
        self.active, self.all, self.next = [], {}, 0
        self.coast_frames = max(1,min(30,coast_frames))

    def update(self, boxes, frame, cut=False):
        if cut: self.active=[]
        self.active=[t for t in self.active if frame-t.last<=self.coast_frames]
        pairs=[]
        for track in self.active:
            for index,box in enumerate(boxes):
                score=iou(track.predict(frame),box)
                if score>=.15: pairs.append((score,track.number,index))
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
        coast=[(t.number,t.predict(frame)) for t in self.active if t.number not in used_tracks]
        return assigned,coast

def padded(box, amount=.10):
    x,y,r,b=box;dx=(r-x)*amount;dy=(b-y)*amount
    return [round(max(0,x-dx),6),round(max(0,y-dy),6),round(min(1,r+dx),6),round(min(1,b+dy),6)]

