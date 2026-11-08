'use strict';
// Questions before a motion advert, per kind of business. Each kind has story sections (opening,
// products or services, the experience, ordering, trust, offer, closing, style); every section asks
// several questions with suggestions to tap and a free «أخرى» line. The answers become a story plan
// the director follows. Kinds follow the businesses that most often order motion adverts
// (food and cafés, retail and e-commerce, apps, clinics, real estate, education, services).
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarQuestions=api;})(globalThis,()=>{
 const key=t=>String(t||'').normalize('NFKC').toLowerCase().replace(/[ً-ٰٟـ]/g,'').replace(/[أإآٱ]/g,'ا').replace(/ة/g,'ه').replace(/[ىئ]/g,'ي').replace(/ؤ/g,'و').trim();
 const o=(value,label,re)=>[value,label,re];

 // Shared suggestions, reused by several kinds.
 const HOURS=[o('h24','مفتوح 24 ساعة',/24|٢٤|مدار الساعه|طول اليوم|ليل نهار/),o('late','نسهر لين آخر الليل',/لين الفجر|اخر الليل|سهر/),o('early','نفتح من الصبح بدري',/الصبح|الفجر|بدري/),o('weekend','نفتح الويكند والإجازات',/ويكند|اجاز|الجمعه/)];
 const APPS=[o('hunger','هنقرستيشن',/هنقر/),o('keeta','كيتا',/كيتا|keeta/),o('jahez','جاهز',/جاهز/),o('mrsool','مرسول',/مرسول/),o('toyou','تويو',/تويو|to ?you/),o('chefz','ذا شفز',/شفز/),o('own','توصيل خاص منا',/توصيل خاص|نوصل/)];
 const PAY=[o('mada','مدى',/مدى/),o('apple','Apple Pay',/apple|ابل باي/),o('tabby','تابي',/تابي|tabby/),o('tamara','تمارا',/تمارا|tamara/),o('cash','كاش',/كاش/)];
 const AUDIENCE=[o('youth','الشباب',/الشباب|شباب/),o('families','العوائل',/عوائل|عوايل|عائل/),o('women','النساء',/نساء|بنات|سيدات/),o('workers','الموظفين',/موظف/),o('students','الطلاب',/طلاب|جامع/),o('drivers','سواق المشاوير والكدادة',/سواق|كداد|مشاوير/),o('tourists','الزوار والسياح',/سياح|زوار/),o('all','الكل',null)];
 const TONE=[o('energetic','حماسية وشبابية'),o('warm','دافية وقريبة من القلب'),o('premium','فخمة وراقية'),o('fun','خفيفة دم ومرحة'),o('simple','بسيطة ومباشرة'),o('local','بلهجة سعودية عفوية')];
 const PACE=[o('fast','سريع ونقلات قوية'),o('medium','متوسط ومتوازن'),o('calm','هادي وسينمائي')];
 const COLORS=[o('brand','ألوان شعاري'),o('warmc','دافية (بني، برتقالي، ذهبي)'),o('dark','فخمة (أسود وذهبي)'),o('bright','مشرقة ومبهجة'),o('cool','هادية (أخضر، أزرق)')];
 const NEWS=[o('opening','افتتاح جديد',/افتتاح|افتتحنا/),o('branch','فرع جديد',/فرع جديد/),o('product','منتج أو صنف جديد',null),o('event','فعالية أو بطولة (مثل بطولة بلوت)',/بطوله|فعاليه|بلوت/),o('season','عرض موسمي (رمضان، العيد، الصيف، الشتاء)',/رمضان|العيد|الصيف|الشتا/),o('none','ما فيه جديد',null)];
 const OFFER=[o('none','ما فيه عرض حاليًا'),o('discount','خصم بنسبة'),o('bundle','عرض كومبو أو باقة'),o('free','هدية أو شي مجاني مع الطلب'),o('first','خصم أول طلب')];
 const EXTRAS=[o('address','العنوان',/عنوان|شارع|حي /),o('phone','رقم التواصل',/رقم|جوال|واتس/),o('hours','أوقات العمل',/24|ساعه|نفتح/),o('apps','أسماء تطبيقات التوصيل',/هنقر|كيتا|جاهز|مرسول/),o('account','حساباتنا في السوشال',/سناب|تيك|انستا|حساب/),o('map','نص «ابحث عنا في خرائط قوقل»',/خرائط|قوقل ماب|لوكيشن/)];

 // Answers that become their own scene, in finished advert words (never the raw chip label: «مفتوحين 24 ساعة»
 // reads «شغالين على مدار الساعة»). [headline, caption, drawing, words the film must show].
 const SCENES={
  h24:['شغالين على مدار الساعة','ليل نهار، طلبك جاهز متى ما جيت','clock24',['الساعه']],
  late:['سهرتك عندنا لين آخر الليل','نفتح لك لين آخر الليل','clock24',['الليل']],
  early:['نفتح لك من الصبح بدري','بداية يومك تحلى عندنا','clock24',['الصبح']],
  weekend:['معك في الويكند والإجازات','أبوابنا مفتوحة في أيام راحتك','clock24',['الويكند']],
  outdoor:['جلسات خارجية تفتح النفس','قعدة في الهواء الطلق مع أحلى الأجواء','outdoor',['خارجي']],
  indoor:['جلسات داخلية مكيفة','قعدة مريحة وهادية بعيد عن الحر','seating',['داخلي']],
  screens:['المباريات على الشاشة الكبيرة','تابع مبارياتك مع الربع على شاشات كبيرة','screen',['شاش']],
  cards:['طاولات بلوت جاهزة','جيب ربعك وخل السهرة تحلى','cards',['بلوت']],
  console:['تحدّ ربعك على البلايستيشن','أجهزة جاهزة وشاشات كبيرة للعب','screen',['بلايستيشن']],
  lights:['أجواء وإضاءة تفتح النفس','زينة وإضاءة تخلي قعدتك أحلى','outdoor',['اضاء']],
  parking:['مواقف قريبة وسهلة','الوصول لنا سهل وبدون لف','location',['مواقف']],
  drive:['طلبك يوصلك وأنت بسيارتك','بدون ما تنزل، نجيب طلبك للسيارة','delivery',['سيار']],
  quiet:['مكان هادي لقعدتك','ركن مريح تريّح فيه بالك','seating',['هادي']],
  family:['قسم خاص للعوائل','خصوصية وراحة لك ولأهلك','people',['عوائل']],
  kids:['ركن خاص للأطفال','يلعبون ويستانسون وأنت مرتاح','people',['اطفال']],
  opening:['افتتحنا!','تعال احتفل معنا بالافتتاح','stars',['افتتح']],
  branch:['فرعنا الجديد صار قريب منك','تعال زورنا في الفرع الجديد','location',['فرع']]
 };
 const SOCIAL=[o('snap','سناب شات',/سناب/),o('tiktok','تيك توك',/تيك/),o('insta','انستقرام',/انستا|انستقرام/),o('x','إكس (تويتر)',/تويتر|اكس/),o('whatsapp','واتساب',/واتس/)];
 const NAME_LABEL={app:'وش اسم التطبيق؟',education:'وش اسم الجهة أو المعهد؟',realestate:'وش اسم المكتب أو المشروع؟',clinic:'وش اسم العيادة؟',gym:'وش اسم النادي؟',events:'وش اسم الفعالية أو الجهة؟',retail:'وش اسم المتجر؟'};
 // Each kind: label, how it is recognised, and its own suggestions per section.
 const KINDS={
  cafe:{label:'كافيه أو مقهى (شاي، قهوة، فطائر)',detect:/شاي|شاهي|قهوه|كافيه|كوفي|مقهي|كرك|فطير|فطاير|معجنات|سناك|مشروبات/,
   hook:[o('coals','الشاي يتحضر على الجمر قدامك',/جمر|فحم/),o('taste','الطعم اللي ما تلقاه بمكان ثاني'),o('h24','مفتوحين 24 ساعة',/24|٢٤/),o('nights','سهرات الشباب والمباريات',/مباريات|مباراه|سهر/),o('price','أسعار في متناول الكل',/سعر|اسعار|ريال/),o('signature','صنفنا المشهور',null),o('vibe','الجلسة والأجواء',/جلس|اجواء/)],
   sells:['وش أكثر شي ينطلب عندكم؟',[o('coalTea','شاي الجمر',/شاي جمر|جمر/),o('karak','كرك',/كرك/),o('milkTea','شاي حليب',/حليب/),o('arabic','قهوة عربية',/قهوه عربيه|دله/),o('specialty','قهوة مختصة',/مختصه|لاتيه|اسبريسو/),o('zaatar','فطائر مالحة',/زعتر|جبن|لبنه|فطير|فطاير/),o('sweetPie','فطائر حلوة',/عسل|نوتيلا/),o('fries','بطاطس',/بطاطس/),o('cold','مشروبات باردة وعصيرات',/عصير|بارد/),o('sweets','حلا وكيك',/حلا|كيك|حلويات/)]],
   quality:['وش يميز اللي تقدمونه؟',[o('fresh','يتحضر طازج على الطلب'),o('coals','على الجمر مو على غاز',/جمر|فحم/),o('recipe','وصفة خاصة فينا'),o('size','كميات كبيرة وتشبع'),o('consistent','نفس الطعم كل مرة'),o('quality','مكونات ممتازة'),o('fast','تجهيز سريع')]],
   place:['وش يميز المكان؟',[o('outdoor','جلسات خارجية',/خارجي|برا/),o('indoor','جلسات داخلية مكيفة',/داخلي|مكيف/),o('screens','شاشات لعرض المباريات',/شاش|مباريات|مباراه/),o('cards','بلوت وجكارو',/بلوت|جكارو/),o('console','بلايستيشن',/بلايستيشن|سوني/),o('lights','إضاءة وزينة حلوة',/اضاء|زينه/),o('parking','مواقف قريبة وسهلة',/مواقف/),o('drive','تاخذ طلبك وانت بالسيارة',/سياره|درايف/),o('quiet','مكان هادي للقعدة',/هادي/)]],
   moment:['متى أحلى وقت لزيارتكم؟',[o('morning','الصبح قبل الدوام'),o('evening','العصر والمغرب'),o('night','السهرة',/سهر/),o('match','وقت المباريات',/مباريات|مباراه/),o('anytime','أي وقت',/24|٢٤/)]],
   audience:AUDIENCE,cta:[o('visit','زورونا في فرعنا',/زورونا|فرعنا/),o('order','اطلبوا من تطبيقات التوصيل',/هنقر|كيتا|توصيل|تطبيقات/),o('both','زورونا أو اطلبوا من التطبيقات'),o('follow','تابعونا')]},
  restaurant:{label:'مطعم أو وجبات سريعة',detect:/مطعم|وجبات|برجر|برقر|بيتزا|شاورما|مشويات|بروستد|مندي|كبسه|سندوتش|ساندويتش/,
   hook:[o('dish','طبقنا المشهور',null),o('taste','طعم ما يتقاوم'),o('portion','كميات تشبع'),o('price','أسعار تناسب الكل',/سعر|ريال/),o('fresh','كل شي يتحضر طازج',/طازج/),o('fast','وجبتك جاهزة بسرعة')],
   sells:['وش أشهر أطباقكم؟',[o('burger','برجر',/برجر|برقر/),o('shawarma','شاورما',/شاورما/),o('grill','مشويات',/مشوي/),o('broast','بروستد ودجاج',/بروستد|دجاج/),o('rice','رز ومندي وكبسة',/مندي|كبسه|رز/),o('pizza','بيتزا',/بيتزا/),o('sandwich','سندويتشات',/سندو|ساندو/),o('sides','مقبلات وبطاطس',/بطاطس|مقبلات/),o('drinks','مشروبات وعصيرات',/عصير/),o('desserts','حلويات',/حلويات|حلا/)]],
   quality:['وش يميز أكلكم؟',[o('fresh','لحم ودجاج طازج يوميًا'),o('sauce','صوص وتتبيلة خاصة'),o('charcoal','على الفحم',/فحم|جمر/),o('homemade','طعم بيتي'),o('healthy','خيارات صحية',/صحي/),o('family','وجبات عائلية',/عائل|عوائل/)]],
   place:['وش يميز المكان؟',[o('family','قسم عوائل',/عوائل|عائل/),o('outdoor','جلسات خارجية',/خارجي/),o('kids','ركن أطفال',/اطفال/),o('drive','طلب من السيارة',/سياره|درايف/),o('clean','نظافة واضحة'),o('parking','مواقف سهلة',/مواقف/)]],
   moment:['متى الناس تجيكم أكثر؟',[o('lunch','الغدا'),o('dinner','العشا'),o('late','آخر الليل',/سهر|ليل/),o('weekend','الويكند')]],
   audience:AUDIENCE,cta:[o('visit','زورونا في فرعنا',/زورونا|فرعنا/),o('order','اطلبوا من تطبيقات التوصيل',/هنقر|كيتا|توصيل/),o('both','زورونا أو اطلبوا من التطبيقات'),o('call','اتصلوا واطلبوا',/اتصل/)]},
  sweets:{label:'حلويات أو مخبز',detect:/حلويات|حلا|كيك|مخبز|مخبوزات|كنافه|دونات|بسكويت|شوكولا|ايسكريم|بوظه/,
   hook:[o('signature','حلانا المشهور'),o('fresh','يطلع من الفرن الحين',/طازج|فرن/),o('gift','هدية تبيض الوجه',/هدايا|هديه/),o('occasion','لكل مناسباتكم',/مناسب|عزيم/),o('price','أسعار حلوة')],
   sells:['وش أشهر شي عندكم؟',[o('cake','كيك',/كيك/),o('kunafa','كنافة',/كنافه/),o('cookies','كوكيز وبسكويت',/كوكيز|بسكويت/),o('donuts','دونات',/دونات/),o('bread','خبز ومعجنات',/خبز|معجنات/),o('chocolate','شوكولاتة',/شوكولا/),o('icecream','آيس كريم',/ايسكريم|ايس كريم|بوظه/),o('trays','صواني ضيافة',/صواني|ضيافه/)]],
   quality:['وش يميزها؟',[o('daily','تنخبز يوميًا'),o('ingredients','مكونات فاخرة'),o('custom','نسوي طلبات خاصة بالاسم والشكل'),o('lessSugar','خيارات قليلة السكر',/سكر|دايت/),o('packaging','تغليف أنيق')]],
   place:['وش يميز تجربتكم؟',[o('display','واجهة عرض تفتح النفس'),o('seating','جلسات',/جلس/),o('preorder','حجز مسبق للمناسبات',/حجز/),o('delivery','توصيل',/توصيل/)]],
   moment:['لأي مناسبة الناس تطلب منكم؟',[o('guests','الضيوف والعزايم'),o('birthdays','أعياد الميلاد'),o('weddings','الأعراس والملكات'),o('ramadan','رمضان والعيد'),o('daily','حلا يومي')]],
   audience:AUDIENCE,cta:[o('visit','زورونا في فرعنا',/زورونا|فرعنا/),o('order','اطلبوا من التطبيقات',/هنقر|كيتا|توصيل/),o('preorder','احجزوا طلبكم الخاص'),o('call','تواصلوا معنا واتساب',/واتس/)]},
  electronics:{label:'جوالات وإلكترونيات واتصالات',detect:/جوالات|محل جوال|متجر جوال|اتصالات|الكترونيات|ساعات ذكيه|ساعه ذكيه|سماعات|شواحن|اكسسوارات جوال|ايفون|سامسونج|لابتوب|صيانه جوال/,
   hook:[o('latest','أحدث الأجهزة عندنا أول',/جديد|احدث/),o('price','أسعار تنافسية',/سعر|اسعار/),o('install','تقسيط بدون فوائد',/تقسيط|تابي|تمارا/),o('repair','صيانة سريعة قدامك',/صيانه/),o('original','كل منتجاتنا أصلية',/اصلي/),o('accessories','كل إكسسوارات جوالك في مكان واحد',/اكسسوار/)],
   sells:['وش تبيعون؟',[o('phones','جوالات',/جوال|ايفون|سامسونج/),o('watches','ساعات ذكية',/ساعات|ساعه/),o('earbuds','سماعات',/سماع/),o('chargers','شواحن وكيابل',/شاحن|شواحن|كيبل/),o('cases','كفرات وحمايات شاشة',/كفر|حمايه/),o('laptops','لابتوبات وأجهزة لوحية',/لابتوب|ايباد|تابلت/),o('sim','شرايح وباقات',/شرايح|باقات|شريحه/),o('gaming','أجهزة وألعاب',/بلايستيشن|سوني|العاب/)]],
   quality:['وش يميزكم؟',[o('original','منتجات أصلية بضمان',/اصلي|ضمان/),o('warranty','ضمان سنة أو أكثر',/ضمان/),o('tradein','استبدال جهازك القديم',/استبدال/),o('setup','نجهز لك الجهاز وننقل بياناتك'),o('advice','نساعدك تختار الأنسب'),o('fastRepair','صيانة نفس اليوم',/صيانه/)]],
   place:['وش يميز المحل؟',[o('display','تجربة الأجهزة بيدك'),o('location','موقع سهل',/مواقف|موقع/),o('online','متجر إلكتروني كمان',/متجر الكتروني|موقع/),o('delivery','توصيل لباب البيت',/توصيل/)]],
   moment:['وش يخلي الناس تشتري الحين؟',[o('launch','نزول جهاز جديد'),o('season','عروض المواسم'),o('school','العودة للمدارس'),o('gift','هدايا')]],
   audience:AUDIENCE,cta:[o('visit','زورونا في فرعنا',/زورونا|فرعنا/),o('online','اطلبوا من متجرنا الإلكتروني',/متجر|موقع/),o('whatsapp','كلمونا واتساب',/واتس/),o('call','اتصلوا علينا',/اتصل/)]},
  fashion:{label:'عطور أو أزياء أو عبايات',detect:/عطر|عطور|عود|بخور|ازياء|ملابس|عبايات|عبايه|شماغ|ثياب|ثوب|فساتين|احذيه|شنط|مجوهرات|ذهب/,
   hook:[o('signature','عطرنا أو قطعتنا المميزة'),o('luxury','فخامة بسعر معقول',/فخام|سعر/),o('new','وصل الجديد',/جديد|وصل/),o('exclusive','تصاميم ما تلقاها بمكان ثاني',/حصري/),o('gift','هدية تفرح',/هدي/)],
   sells:['وش تبيعون؟',[o('oud','عود ودهن عود',/عود/),o('perfume','عطور',/عطر/),o('bakhoor','بخور ومعطرات',/بخور/),o('abaya','عبايات',/عباي/),o('thobe','ثياب وشمغ',/ثوب|ثياب|شماغ/),o('dresses','فساتين',/فستان|فساتين/),o('shoes','أحذية وشنط',/احذيه|شنط/),o('jewelry','مجوهرات وإكسسوارات',/مجوهرات|ذهب|اكسسوار/)]],
   quality:['وش يميزكم؟',[o('lasting','ثبات يدوم',/ثبات/),o('quality','خامات ممتازة',/خامه|خامات/),o('tailor','تفصيل على المقاس',/تفصيل/),o('packaging','تغليف هدايا فاخر',/تغليف/),o('samples','تجربة قبل الشراء'),o('price','أسعار تنافسية',/سعر/)]],
   place:['وش يميز تجربتكم؟',[o('boutique','محل أنيق'),o('online','متجر إلكتروني',/متجر|موقع/),o('delivery','توصيل سريع',/توصيل/),o('advice','نساعدك تختار')]],
   moment:['لأي مناسبة؟',[o('eid','العيد ورمضان'),o('wedding','الأعراس'),o('daily','الاستخدام اليومي'),o('gift','الهدايا')]],
   audience:AUDIENCE,cta:[o('visit','زورونا في فرعنا',/زورونا|فرعنا/),o('online','اطلبوا من متجرنا الإلكتروني',/متجر|موقع/),o('whatsapp','كلمونا واتساب',/واتس/)]},
  retail:{label:'محل أو متجر (بقالة، هدايا، أدوات…)',detect:/بقاله|سوبرماركت|ماركت|متجر|هدايا|العاب اطفال|ادوات|قرطاسيه|ورد|زهور|محمصه|تمور|بهارات/,
   hook:[o('variety','كل اللي تحتاجه بمكان واحد'),o('price','أسعار أرخص',/سعر|ارخص/),o('quality','جودة تثق فيها'),o('new','منتجات جديدة كل أسبوع',/جديد/),o('fast','خدمة سريعة')],
   sells:['وش أهم منتجاتكم؟',[],'اكتب أهم منتجاتكم مفصولة بفاصلة'],
   quality:['وش يميزكم؟',[o('variety','تشكيلة واسعة'),o('price','أسعار منافسة',/سعر/),o('fresh','منتجات طازجة',/طازج/),o('local','منتجات محلية',/محلي/),o('wrap','تغليف هدايا',/تغليف|هدايا/)]],
   place:['وش يميز المحل؟',[o('location','موقع قريب وسهل'),o('parking','مواقف',/مواقف/),o('online','متجر إلكتروني',/متجر الكتروني|موقع/),o('delivery','توصيل',/توصيل/)]],
   moment:null,audience:AUDIENCE,cta:[o('visit','زورونا في فرعنا',/زورونا|فرعنا/),o('online','اطلبوا أونلاين',/متجر|موقع/),o('whatsapp','كلمونا واتساب',/واتس/)]},
  beauty:{label:'صالون أو مشغل أو حلاق',detect:/صالون|مشغل|حلاق|حلاقه|تجميل|اظافر|مكياج|بشره|سبا|مساج|شعر/,
   hook:[o('result','نتيجة تبين من أول مرة'),o('expert','خبيرات أو حلاقين محترفين',/خبر|محترف/),o('relax','تجربة راحة واسترخاء',/استرخاء|سبا/),o('price','أسعار مناسبة',/سعر/),o('occasion','جهزي لمناسبتك',/مناسب|عرس/)],
   sells:['وش خدماتكم؟',[o('hair','قص وصبغ وعناية بالشعر',/شعر|قص|صبغ/),o('beard','حلاقة ولحية',/لحيه|حلاقه/),o('makeup','مكياج',/مكياج/),o('nails','أظافر',/اظافر/),o('skin','عناية بالبشرة',/بشره/),o('spa','سبا ومساج',/سبا|مساج/),o('bridal','باقات العرايس',/عروس|عرايس/)]],
   quality:['وش يميزكم؟',[o('brands','منتجات عالمية'),o('clean','تعقيم ونظافة'),o('booking','حجز سهل بدون انتظار',/حجز|موعد/),o('home','خدمة منزلية',/منزل/),o('privacy','خصوصية تامة')]],
   place:['وش يميز المكان؟',[o('design','ديكور مريح'),o('private','غرف خاصة'),o('parking','مواقف',/مواقف/),o('kids','ركن أطفال')]],
   moment:['متى الناس تحتاجكم؟',[o('wedding','قبل الأعراس'),o('eid','قبل العيد'),o('weekly','عناية أسبوعية'),o('event','قبل أي مناسبة')]],
   audience:AUDIENCE,cta:[o('book','احجزوا موعدكم الحين',/احجز|موعد/),o('visit','زورونا',/زورونا/),o('whatsapp','احجزوا واتساب',/واتس/)]},
  clinic:{label:'عيادة أو مجمع طبي',detect:/عياده|مجمع طبي|اسنان|جلديه|عيون|علاج طبيعي|مستوصف|طبيب|دكتور/,
   hook:[o('care','صحتك تهمنا'),o('experts','نخبة من الأطباء',/طبيب|دكتور/),o('tech','أجهزة حديثة',/اجهزه|حديث/),o('fast','مواعيد بدون انتظار',/موعد/),o('insurance','نقبل التأمين',/تامين/)],
   sells:['وش التخصصات أو الخدمات؟',[o('dental','أسنان',/اسنان/),o('derma','جلدية وتجميل',/جلديه|تجميل/),o('eyes','عيون',/عيون/),o('physio','علاج طبيعي',/علاج طبيعي/),o('family','طب عام وأسرة',/عام|اسره/),o('lab','مختبر وأشعة',/مختبر|اشعه/)]],
   quality:['وش يميزكم؟',[o('licensed','أطباء معتمدين'),o('insurance','نقبل أغلب شركات التأمين',/تامين/),o('install','تقسيط',/تقسيط|تابي|تمارا/),o('sterile','تعقيم عالي'),o('women','طبيبات',/طبيبه|طبيبات/)]],
   place:['وش يميز التجربة؟',[o('online','حجز أونلاين',/حجز|تطبيق/),o('parking','مواقف',/مواقف/),o('comfort','انتظار مريح'),o('evening','دوام مسائي',/مسائي/)]],
   moment:null,audience:AUDIENCE,cta:[o('book','احجز موعدك الحين',/احجز|موعد/),o('call','اتصل علينا',/اتصل/),o('whatsapp','احجز واتساب',/واتس/),o('visit','زورونا',/زورونا/)]},
  gym:{label:'نادي رياضي أو تدريب',detect:/نادي|جيم|gym|لياقه|تمارين|كروس فت|يوغا|مدرب شخصي/,
   hook:[o('change','غيّر شكلك في أسابيع'),o('coach','مدربين معتمدين',/مدرب/),o('equipment','أجهزة حديثة',/اجهزه/),o('price','اشتراك بسعر مناسب',/اشتراك|سعر/),o('h24','مفتوح 24 ساعة',/24|٢٤/)],
   sells:['وش تقدمون؟',[o('weights','حديد وأجهزة',/حديد|اجهزه/),o('cardio','كارديو',/كارديو/),o('classes','كلاسات جماعية',/كلاس/),o('pt','مدرب شخصي',/مدرب شخصي/),o('pool','مسبح',/مسبح/),o('ladies','قسم نسائي',/نسائي|سيدات/),o('nutrition','برامج تغذية',/تغذيه/)]],
   quality:['وش يميزكم؟',[o('clean','نظافة'),o('space','مساحة كبيرة'),o('plans','برامج حسب هدفك'),o('results','نتائج مشتركين حقيقية')]],
   place:['وش يميز المكان؟',[o('parking','مواقف',/مواقف/),o('lockers','غرف تبديل وخزائن'),o('music','أجواء وموسيقى حماسية')]],
   moment:null,audience:AUDIENCE,cta:[o('join','اشترك الحين',/اشترك/),o('trial','جرّب يوم مجاني'),o('visit','زورونا',/زورونا/)]},
  education:{label:'تعليم أو تدريب أو دورات',detect:/دوره|دورات|تدريب|اكاديميه|معهد|مدرسه|تعليم|كورس|تحفيظ|لغه انجليزي/,
   hook:[o('career','مهارة تفتح لك وظيفة'),o('fast','تعلّم بوقت قصير'),o('cert','شهادة معتمدة',/معتمد|شهاده/),o('experts','مدربين خبرة',/مدرب|خبر/),o('online','تعلم من بيتك',/اونلاين|عن بعد/)],
   sells:['وش تقدمون؟',[o('lang','لغات',/لغه|انجليزي/),o('tech','برمجة وتقنية',/برمج|تقني/),o('business','إدارة وتسويق',/اداره|تسويق/),o('design','تصميم',/تصميم/),o('kids','دورات أطفال',/اطفال/),o('quran','تحفيظ',/تحفيظ|قران/),o('tutoring','دروس تقوية',/تقويه/)]],
   quality:['وش يميزكم؟',[o('accredited','اعتماد رسمي'),o('practical','تطبيق عملي'),o('small','مجموعات صغيرة'),o('flexible','مواعيد مرنة'),o('install','تقسيط الرسوم',/تقسيط/)]],
   place:null,moment:null,audience:AUDIENCE,cta:[o('register','سجّل الحين',/سجل/),o('whatsapp','كلمنا واتساب',/واتس/),o('visit','زورونا',/زورونا/)]},
  realestate:{label:'عقار أو مشروع سكني',detect:/عقار|شقق|شقه|فلل|فيلا|ارض|اراضي|مخطط|مشروع سكني|ايجار|تمليك/,
   hook:[o('home','بيتك اللي تحلم فيه'),o('location','موقع استراتيجي',/موقع/),o('price','أسعار تبدأ من…',/يبدا|سعر/),o('finance','تمويل بنكي مدعوم',/تمويل|سكني/),o('ready','جاهز للسكن',/جاهز/)],
   sells:['وش المعروض؟',[o('apartments','شقق',/شقق|شقه/),o('villas','فلل',/فلل|فيلا/),o('land','أراضي',/ارض|اراضي/),o('commercial','محلات ومكاتب',/محل|مكتب/),o('rent','إيجار',/ايجار/)]],
   quality:['وش يميز المشروع؟',[o('finish','تشطيب فاخر'),o('warranty','ضمانات على الهيكل والتمديدات',/ضمان/),o('smart','بيت ذكي',/ذكي/),o('services','قريب من الخدمات والمدارس'),o('privacy','خصوصية ومداخل مستقلة')]],
   place:['وش حول المشروع؟',[o('schools','مدارس',/مدارس/),o('mosque','مسجد',/مسجد/),o('mall','مراكز تسوق',/مول/),o('park','حدائق وممشى',/حديقه|ممشى/)]],
   moment:null,audience:AUDIENCE,cta:[o('visit','زوروا المشروع',/زور/),o('call','اتصل للحجز',/اتصل/),o('whatsapp','كلمنا واتساب',/واتس/)]},
  car:{label:'خدمات سيارات (مغسلة، ورشة، تأجير، قطع)',detect:/سياره|سيارات|مغسله|ورشه|تاجير|قطع غيار|زيوت|كفرات|تظليل|نانو/,
   hook:[o('shine','سيارتك ترجع كأنها جديدة'),o('fast','خدمة وأنت تنتظر'),o('price','أسعار واضحة',/سعر/),o('experts','فنيين خبرة',/فني|خبر/),o('warranty','ضمان على الشغل',/ضمان/)],
   sells:['وش خدماتكم؟',[o('wash','غسيل وتلميع',/غسيل|تلميع/),o('nano','نانو سيراميك وحماية',/نانو|سيراميك|حمايه/),o('tint','تظليل',/تظليل/),o('oil','غيار زيت',/زيت|زيوت/),o('tires','كفرات',/كفرات/),o('repair','صيانة ميكانيكا',/صيانه|ميكانيك/),o('rent','تأجير سيارات',/تاجير/),o('parts','قطع غيار',/قطع/)]],
   quality:['وش يميزكم؟',[o('original','قطع ومواد أصلية',/اصلي/),o('pickup','نستلم السيارة من بيتك'),o('booking','حجز بدون انتظار',/حجز/),o('waiting','صالة انتظار مريحة')]],
   place:null,moment:null,audience:AUDIENCE,cta:[o('visit','زورونا',/زورونا/),o('book','احجز موعدك',/احجز/),o('whatsapp','كلمنا واتساب',/واتس/)]},
  home:{label:'خدمات منزلية (صيانة، تنظيف، نقل)',detect:/صيانه|تنظيف|نقل عفش|سباكه|كهرباء|مكيفات|مكافحه|تكييف|دهانات/,
   hook:[o('fast','نوصلك بنفس اليوم'),o('trust','فنيين موثوقين',/موثوق|فني/),o('price','سعر واضح قبل الشغل',/سعر/),o('warranty','ضمان على الشغل',/ضمان/),o('clean','شغل نظيف ومرتب')],
   sells:['وش خدماتكم؟',[o('ac','مكيفات',/مكيف|تكييف/),o('plumbing','سباكة',/سباكه/),o('electric','كهرباء',/كهرباء/),o('cleaning','تنظيف',/تنظيف/),o('moving','نقل عفش',/نقل/),o('pest','مكافحة حشرات',/مكافحه|حشرات/),o('paint','دهانات',/دهان/)]],
   quality:['وش يميزكم؟',[o('licensed','عمالة مدربة'),o('h24','خدمة طوارئ 24 ساعة',/24|٢٤/),o('tools','معدات حديثة'),o('install','دفع بعد الإنجاز')]],
   place:null,moment:null,audience:AUDIENCE,cta:[o('call','اتصل علينا',/اتصل/),o('whatsapp','اطلب واتساب',/واتس/),o('book','احجز زيارة',/احجز/)]},
  app:{label:'تطبيق أو منصة أو متجر إلكتروني',detect:/(?:^|\s)(?:تطبيقنا|تطبيقي|منصه|منصتنا|متجر الكتروني|متجرنا الالكتروني|موقعنا الالكتروني)|(?:^|\s)تطبيق(?!ات? توصيل)(?!\s+توصيل)/,
   hook:[o('problem','مشكلة نحلها لك'),o('easy','كل شي بضغطة',/سهل|ضغطه/),o('save','وفر وقتك وفلوسك',/وفر/),o('free','مجاني',/مجاني/),o('new','جديد ولأول مرة',/جديد/)],
   sells:['وش يسوي؟',[],'اكتب أهم 3 أشياء يسويها'],
   quality:['وش يميزه عن غيره؟',[o('easy','سهل الاستخدام',/سهل/),o('fast','سريع',/سريع/),o('arabic','عربي بالكامل',/عربي/),o('secure','آمن',/امن|امان/),o('support','دعم سريع',/دعم/),o('free','مجاني أو سعر رمزي',/مجاني/)]],
   place:['وش خطوات الاستخدام؟',[o('download','حمّل التطبيق'),o('signup','سجل بسهولة'),o('choose','اختر اللي تبيه'),o('done','واستلم أو خلّص')]],
   moment:null,audience:AUDIENCE,cta:[o('download','حمّل التطبيق الحين',/حمل/),o('visit','ادخل موقعنا',/موقع/),o('follow','تابعنا',/تابع/)]},
  events:{label:'قاعات ومناسبات وفعاليات',detect:/قاعه|قاعات|مناسبات|فعاليه|حفلات|افراح|تنسيق|ضيافه|كوش/,
   hook:[o('dream','مناسبتك مثل ما تحلم'),o('all','كل التجهيزات علينا'),o('space','قاعة تكفي ضيوفك',/قاعه/),o('price','باقات بأسعار مناسبة',/باقه|سعر/)],
   sells:['وش تقدمون؟',[o('halls','قاعات',/قاعه|قاعات/),o('decor','تنسيق وكوش',/تنسيق|كوش/),o('catering','ضيافة وبوفيه',/ضيافه|بوفيه/),o('photo','تصوير',/تصوير/),o('sound','صوتيات وإضاءة',/صوت|اضاءه/)]],
   quality:['وش يميزكم؟',[o('custom','تصاميم على ذوقك'),o('team','فريق متكامل'),o('packages','باقات مرنة'),o('experience','خبرة سنين',/خبر|سنين/)]],
   place:null,moment:['لأي مناسبة؟',[o('wedding','أعراس وملكات'),o('grad','تخرج'),o('corporate','مناسبات شركات'),o('birthday','أعياد ميلاد')]],
   audience:AUDIENCE,cta:[o('book','احجزوا الحين',/احجز/),o('whatsapp','تواصلوا واتساب',/واتس/),o('visit','زورونا',/زورونا/)]},
  stay:{label:'فندق أو شاليه أو استراحة',detect:/فندق|شاليه|شاليهات|استراحه|منتجع|نزل|مخيم|كشته/,
   hook:[o('escape','هروبك من الزحمة'),o('family','جمعة العائلة',/عائل|عوائل/),o('pool','مسبح خاص',/مسبح/),o('view','إطلالة تجنن',/اطلاله/),o('price','أسعار الليلة تبدأ من…',/سعر|ليله/)],
   sells:['وش عندكم؟',[o('pool','مسبح',/مسبح/),o('kids','ألعاب أطفال',/اطفال/),o('grill','منطقة شواء',/شواء/),o('sessions','جلسات خارجية',/جلس/),o('rooms','غرف نوم',/غرف/),o('football','ملعب',/ملعب/)]],
   quality:['وش يميزكم؟',[o('clean','نظافة عالية'),o('privacy','خصوصية'),o('new','جديد',/جديد/),o('location','موقع قريب',/موقع/)]],
   place:null,moment:['لمين مناسب؟',[o('families','العوائل'),o('friends','الشباب والأصحاب'),o('events','المناسبات الصغيرة'),o('weekend','نهاية الأسبوع')]],
   audience:AUDIENCE,cta:[o('book','احجز الحين',/احجز/),o('whatsapp','احجز واتساب',/واتس/),o('call','اتصل علينا',/اتصل/)]},
  general:{label:'نشاط ثاني',detect:null,
   hook:[o('benefit','أكبر فايدة تقدمها للعميل'),o('price','السعر',/سعر/),o('quality','الجودة'),o('speed','السرعة'),o('trust','الثقة والخبرة',/خبر|ثقه/)],
   sells:['وش تقدمون؟',[],'اكتب منتجاتكم أو خدماتكم'],
   quality:['وش يميزكم؟',[o('price','سعر مناسب',/سعر/),o('quality','جودة عالية'),o('speed','سرعة',/سريع/),o('support','خدمة عملاء'),o('experience','خبرة سنين',/خبر|سنين/)]],
   place:null,moment:null,audience:AUDIENCE,cta:[o('visit','زورونا',/زورونا/),o('call','تواصلوا معنا',/اتصل|تواصل/),o('whatsapp','واتساب',/واتس/),o('follow','تابعونا',/تابع/)]}
 };
 const ORDER=['cafe','restaurant','sweets','electronics','fashion','retail','beauty','clinic','gym','education','realestate','car','home','app','events','stay','general'];

 // Food delivery apps alone (هنقرستيشن، كيتا…) point to food; «تطبيقات التوصيل» never means an app business.
 function detect(text){const t=key(text);for(const id of ORDER){const re=KINDS[id].detect;if(re&&re.test(t))return id;}if(/هنقر|كيتا|جاهز|مرسول|ذا شفز/.test(t))return 'cafe';return '';}

 function chips(name,label,section,list,multi,placeholder,text){const t=key(text);return {name,label,section,type:'chips',multi,options:list.map(([v,l])=>[v,l]),selected:list.filter(([,,re])=>re&&re.test(t)).map(([v])=>v).slice(0,multi?8:1),other:'',value:'',placeholder:placeholder||'أخرى: اكتب هنا'};}
 function kindField(kind){return {name:'business',label:'وش نوع نشاطك؟',section:'نشاطك',type:'chips',multi:false,options:ORDER.map(id=>[id,KINDS[id].label]),selected:kind?[kind]:[],other:'',value:kind?KINDS[kind].label:'',placeholder:'أخرى: اكتب نوع نشاطك'};}

 // The full card: the kind first, then the story sections with several questions each.
 function build(kind,text){
  const k=KINDS[kind]||KINDS.general,f=[kindField(kind)];const q=(name,label,section,list,multi,placeholder)=>f.push(chips(name,label,section,list,multi,placeholder,text));
  q('name',NAME_LABEL[kind]||'وش اسم المحل؟','نشاطك',[],false,'اكتب الاسم بالضبط مثل ما تبيه يظهر');
  const opening='الافتتاح: أول ما يشوفه الناس';
  q('hook','وش أقوى شي تبيه يعلق في بال الناس من أول ثانية؟',opening,k.hook,false,'أخرى: اكتب فكرتك للبداية');
  q('feeling','وش الإحساس اللي تبي الناس تطلع فيه؟',opening,[o('crave','نفسي أروح الحين'),o('trust','أثق فيهم'),o('wow','واو! ما توقعت'),o('belong','هذا مكاننا'),o('smart','قرار ذكي وموفر')],false);
  const products=kind==='app'?'وش يسوي':'المنتجات أو الخدمات';
  q('sells',k.sells[0],products,k.sells[1],true,k.sells[2]||'أخرى: مثلًا صنف ما ذكرته');
  q('star','وش الشي اللي تفتخر فيه أكثر من غيره؟',products,k.sells[1].length?k.sells[1]:[],false,'اكتب اسمه بالضبط');
  q('quality',k.quality[0],products,k.quality[1],true);
  if(k.place)q('place',k.place[0],kind==='app'?'طريقة الاستخدام':'المكان والتجربة',k.place[1],true);
  if(k.moment)q('moment',k.moment[0],kind==='app'?'طريقة الاستخدام':'المكان والتجربة',k.moment[1],true);
  const service='الطلب والخدمة';
  if(!['app','education','realestate'].includes(kind))q('hours','أوقات العمل؟',service,HOURS,true,'أخرى: مثلًا من 4 العصر إلى 2 الفجر');
  if(['cafe','restaurant','sweets','retail','fashion','electronics'].includes(kind))q('apps','تطبيقات التوصيل اللي عندكم؟',service,APPS,true,'أخرى: تطبيق ما ذكرته');
  q('pay','طرق الدفع اللي تبي تبينها؟',service,PAY,true);
  const trust='الثقة';
  q('proof','وش يثبت للناس إنكم الأفضل؟',trust,[o('years','خبرة سنين',/سنين|سنه|منذ/),o('customers','عدد كبير من العملاء'),o('ratings','تقييمات عالية في قوقل والتطبيقات',/تقييم/),o('branches','أكثر من فرع',/فروع/),o('famous','معروفين في المنطقة')],true,'أخرى: مثلًا من 2015، أو 4.8 في قوقل');
  const offer='العروض والجديد';
  q('offer','عندكم عرض حالي؟',offer,OFFER,false,'اكتب العرض بالضبط: مثلًا الشاي بريالين');
  q('news','عندكم شي جديد؟',offer,NEWS,false,'اكتب الجديد بالضبط: مثلًا بيتزا، أو بطولة بلوت يوم الخميس');
  const closing='الختام';
  q('cta','وش تبي الناس تسوي بعد الإعلان؟',closing,k.cta,false,'أخرى: اكتب جملة الختام اللي تبيها');
  q('extras','وش يظهر في المشهد الأخير؟',closing,EXTRAS,true);
  q('social','حساباتكم في التواصل الاجتماعي؟ (اختياري)',closing,SOCIAL,true,'اكتب المعرّف: مثلًا ‎@shahijamr');
  const style='الشكل والأسلوب';
  q('audience','مين تبي يوصله الإعلان؟',style,k.audience,true,'أخرى: مثلًا سواق المشاوير');
  q('tone','وش روح الإعلان؟',style,TONE,false,'أخرى: اكتب الأسلوب اللي تبيه');
  q('pace','وش سرعة الإعلان؟',style,PACE,false);
  q('colors','وش الألوان اللي تبيها؟',style,COLORS,false);
  q('never','شي ما تبيعونه أو ما تبيه يُذكر؟','ممنوعات',[o('none','لا يوجد')],false,'مثلًا: ما نبيع لحوم، أو لا تذكر الأسعار');
  return f;
 }

 function labels(f){if(!f)return [];const map=new Map((f.options||[]).map(([v,l])=>[v,l]));return [...(f.selected||[]).filter(v=>v!=='none').map(v=>map.get(v)||v),...String(f.other||'').split(/[،,\n]+/).map(x=>x.trim()).filter(Boolean)];}

 // The answers as a story plan for the director, grouped by section; empty answers add nothing.
 // «اكتب X في آخر مشهد» and «لا تذكر X» lines are read by the brief contract as required and forbidden copy.
 function plan(fields,text=''){
  const get=n=>(fields||[]).find(f=>f.name===n),v=n=>labels(get(n)),sel=n=>(get(n)?.selected||[]).filter(x=>x!=='none'),typed=n=>String(get(n)?.other||'').trim(),lines=[],scenes=[],tail=[];
  const kind=(get('business')?.selected||[])[0];const kindLabel=v('business')[0];
  const add=(label,n)=>{const x=v(n);if(x.length)lines.push('• '+label+': '+x.join('، '));};
  const scene=(title,caption,art,must)=>{if(title&&scenes.length<6)scenes.push('مشهد مطلوب: «'+title+'» | «'+caption+'»'+(art?' | رسمة: '+art:'')+(must&&must.length?' | لازم: '+must.join('، '):''));};
  const fromBank=id=>{const x=SCENES[id];if(x)scene(x[0],x[1],x[2],x[3]);};
  if(kindLabel)lines.push('نوع النشاط: '+kindLabel);
  // The name the owner typed is the one the film writes (never a reading of the logo).
  const name=typed('name').replace(/^[«"']+|[»"']+$/g,'').trim();
  if(name)lines.push((kind==='app'?'اسم التطبيق':'اسم المحل')+': «'+name+'»');
  // The opening idea in finished words («مفتوحين 24 ساعة» reads «شغالين على مدار الساعة»).
  {const hook=sel('hook')[0],own=typed('hook');const said=own||(SCENES[hook]?SCENES[hook][0]:v('hook')[0]);if(said)lines.push('• الافتتاح (أول مشهد بعد الشعار يكون عن هذا): '+said);}add('الإحساس المطلوب','feeling');
  add('نبيع ونقدم','sells');add('أفخر شي عندنا (يأخذ مشهد خاص)','star');add('مميزاتنا','quality');add('أحلى وقت للزيارة','moment');
  // What is new comes first: a new item, an event («بطولة بلوت»), an opening.
  const news=sel('news'),newsText=typed('news');
  if(newsText&&news.includes('event'))scene(newsText.slice(0,40),/بطول/.test(key(newsText))?'سجّل فريقك وخلك جاهز للتحدي':'لا تفوتها، الكل مدعو','',[newsText.split(/\s+/).slice(0,2).join(' ')]);
  else if(newsText)scene(newsText.slice(0,40),'جديد عندنا، تعال جرّبه','',[newsText.split(/\s+/)[0]]);
  for(const id of news)if(SCENES[id])fromBank(id);
  const offer=get('offer');const offerText=String(offer?.other||'').trim();
  if(offerText){lines.push('• العرض الحالي (اكتبه كما هو): '+offerText);scene('عرض خاص',offerText.slice(0,90),'offer',[offerText.split(/\s+/).slice(0,2).join(' ')]);}
  else if((offer?.selected||[]).includes('none'))lines.push('• لا يوجد عرض حالي؛ لا تخترع خصمًا');else add('نوع العرض','offer');
  // The place, the hours and the delivery apps the owner ticked each get a scene.
  for(const id of sel('place'))fromBank(id);
  for(const id of sel('hours').slice(0,1))fromBank(id);
  const apps=sel('apps').filter(id=>id!=='own').map(id=>(get('apps').options.find(([x])=>x===id)||[])[1]).filter(Boolean).concat(typed('apps')?typed('apps').split(/[،,]+/).map(x=>x.trim()).filter(Boolean):[]);
  if(apps.length)scene('اطلبنا من جوالك','موجودين في '+apps.slice(0,-1).join('، ')+(apps.length>1?' و':'')+apps[apps.length-1],'delivery',apps);
  else if(sel('apps').includes('own'))scene('نوصل لك لين بابك','توصيلنا الخاص يجيب طلبك وهو حار','delivery',['نوصل']);
  add('المكان والتجربة','place');add('أوقات العمل','hours');if(apps.length)lines.push('• تطبيقات التوصيل اللي نتعامل معها: '+apps.join('، '));add('طرق الدفع','pay');add('إثبات الثقة','proof');
  add('الجمهور','audience');add('روح الإعلان','tone');add('سرعة الإعلان','pace');add('الألوان','colors');
  // The closing card: the call to action, then what the owner ticked for it.
  const cta=v('cta')[0];const said=key(text);if(cta&&!said.includes(key(cta)))tail.push('اكتب «'+cta+'» في آخر مشهد');
  const extras=sel('extras');
  if(extras.includes('hours')&&sel('hours').includes('h24'))tail.push('اكتب «على مدار الساعة» في آخر مشهد');
  if(extras.includes('apps')&&apps.length)tail.push('اكتب «'+apps.join(' - ')+'» في آخر مشهد');
  const handles=(typed('social').match(/@?[A-Za-z0-9_.]{3,30}/g)||[]).map(h=>h.startsWith('@')?h:'@'+h);
  const platforms=sel('social').map(id=>(SOCIAL.find(([x])=>x===id)||[])[1]).filter(Boolean);
  if(handles.length)tail.push('اكتب «تابعونا '+handles[0]+'» في آخر مشهد');
  else if(platforms.length&&(extras.includes('account')||sel('social').length))tail.push('اكتب «تابعونا على '+platforms.slice(0,3).join(' و')+'» في آخر مشهد');
  for(const item of v('never'))tail.push('لا تذكر '+item.replace(/^(?:ما|لا)\s*(?:نبيع|نقدم)\s+/,'').replace(/^لا\s+تذكر\s+/,''));
  if(!lines.length&&!tail.length&&!scenes.length)return '';
  return ['خطة الإعلان من صاحب المحل (موثوقة؛ لا تضف منتجات أو خدمات غيرها):',...lines,
   ...(scenes.length?['مشاهد اختارها صاحب المحل (كل سطر مشهد كامل في الإعلان بنفس الأسماء؛ يجوز تحسين الصياغة):',...scenes]:[]),
   'وزّع هذه المعلومات على المشاهد: كل مشهد فكرة واحدة، وابدأ بالافتتاح المختار، ولا تكرر معلومة في مشهدين.',...tail].join('\n');
 }
 return {KINDS,ORDER,detect,build,plan,labels,key};
});
